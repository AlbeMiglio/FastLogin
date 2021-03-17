package com.github.games647.fastlogin.bukkit.auth.protocollib;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.injector.server.TemporaryPlayerFactory;
import com.comphenix.protocol.reflect.FieldUtils;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.github.games647.craftapi.model.auth.Verification;
import com.github.games647.craftapi.model.skin.SkinProperty;
import com.github.games647.craftapi.resolver.MojangResolver;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import com.github.games647.fastlogin.bukkit.auth.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.auth.protocollib.packet.IncomingPacket.EncryptionReply;
import com.github.games647.fastlogin.bukkit.auth.protocollib.packet.IncomingPacket.LoginStart;
import com.github.games647.fastlogin.bukkit.auth.protocollib.packet.OutgoingPacket.Disconnect;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import org.bukkit.entity.Player;

public class VerifyResponseTask implements Runnable {

    private final FastLoginBukkit plugin;
    private final PacketEvent packetEvent;
    private final KeyPair serverKey;

    private final Player player;

    private final EncryptionReply packet;

    private static Method encryptMethod;
    private static Method cipherMethod;

    protected VerifyResponseTask(FastLoginBukkit plugin, PacketEvent packetEvent, Player player,
                                 EncryptionReply packet, KeyPair keyPair) {
        this.plugin = plugin;
        this.packetEvent = packetEvent;
        this.player = player;
        this.packet = packet;
        this.serverKey = keyPair;
    }

    @Override
    public void run() {
        try {
            BukkitLoginSession session = plugin.getSessionManager().getLoginSession(player.getAddress());
            if (session == null) {
                disconnect("invalid-request", true
                        , "GameProfile {0} tried to send encryption response at invalid state", player.getAddress());
            } else {
                verifyResponse(session);
            }
        } finally {
            //this is a fake packet; it shouldn't be send to the server
            synchronized (packetEvent.getAsyncMarker().getProcessingLock()) {
                packetEvent.setCancelled(true);
            }

            ProtocolLibrary.getProtocolManager().getAsynchronousManager().signalPacketTransmission(packetEvent);
        }
    }

    private void verifyResponse(BukkitLoginSession session) {
        PrivateKey privateKey = serverKey.getPrivate();

        SecretKey loginKey;
        try {
            loginKey = EncryptionUtil.decryptSharedKey(privateKey, packet.getSharedSecret());
        } catch (GeneralSecurityException securityEx) {
            disconnect("error-kick", false, "Cannot decrypt received contents", securityEx);
            return;
        }

        try {
            if (!checkVerifyToken(session) || !enableEncryption(loginKey)) {
                return;
            }
        } catch (Exception ex) {
            disconnect("error-kick", false, "Cannot decrypt received contents", ex);
            return;
        }

        String serverId = EncryptionUtil.getServerIdHashString("", loginKey, serverKey.getPublic());

        String requestedUsername = session.getRequestUsername();
        InetSocketAddress socketAddress = player.getAddress();
        try {
            MojangResolver resolver = plugin.getCore().getResolver();
            InetAddress address = socketAddress.getAddress();
            Optional<Verification> response = resolver.hasJoined(requestedUsername, serverId, address);
            if (response.isPresent()) {
                Verification verification = response.get();
                plugin.getLog().info("Profile {} has a verified premium account: {}", requestedUsername, verification);
                String realUsername = verification.getName();
                if (realUsername == null) {
                    disconnect("invalid-session", true, "Username field null for {}", requestedUsername);
                    return;
                }

                SkinProperty[] properties = verification.getProperties();
                if (properties.length > 0) {
                    session.setSkinProperty(properties[0]);
                }

                session.setVerifiedUsername(realUsername);
                session.setUuid(verification.getId());
                session.setVerified(true);

                setPremiumUUID(session.getUuid());
                receiveFakeStartPacket(realUsername);
            } else {
                //user tried to fake a authentication
                disconnect("invalid-session", true
                        , "GameProfile {0} ({1}) tried to log in with an invalid session ServerId: {2}"
                        , session.getRequestUsername(), socketAddress, serverId);
            }
        } catch (IOException ioEx) {
            disconnect("error-kick", false, "Failed to connect to session server", ioEx);
        }
    }

    private void setPremiumUUID(UUID premiumUUID) {
        if (plugin.getConfig().getBoolean("premiumUuid") && premiumUUID != null) {
            try {
                Object networkManager = getNetworkManager();
                //https://github.com/bergerkiller/CraftSource/blob/master/net.minecraft.server/NetworkManager.java#L69
                FieldUtils.writeField(networkManager, "spoofedUUID", premiumUUID, true);
            } catch (Exception exc) {
                plugin.getLog().error("Error setting premium uuid of {}", player, exc);
            }
        }
    }

    private boolean checkVerifyToken(BukkitLoginSession session) throws GeneralSecurityException {
        byte[] requestVerify = session.getVerifyToken();
        byte[] responseVerify = packet.getEncryptedVerifyToken();

        //https://github.com/bergerkiller/CraftSource/blob/master/net.minecraft.server/LoginListener.java#L182
        if (!Arrays.equals(requestVerify, EncryptionUtil.decrypt(serverKey.getPrivate(), responseVerify))) {
            //check if the verify token are equal to the server sent one
            disconnect("invalid-verify-token", true
                    , "GameProfile {0} ({1}) tried to login with an invalid verify token. Server: {2} Client: {3}"
                    , session.getRequestUsername(), packetEvent.getPlayer().getAddress(), requestVerify, responseVerify);
            return false;
        }

        return true;
    }

    //try to get the networkManager from ProtocolLib
    private Object getNetworkManager() throws IllegalAccessException, ClassNotFoundException {
        Object injectorContainer = TemporaryPlayerFactory.getInjectorFromPlayer(player);

        //ChannelInjector
        Class<?> injectorClass = Class.forName("com.comphenix.protocol.injector.netty.Injector");
        Object rawInjector = FuzzyReflection.getFieldValue(injectorContainer, injectorClass, true);
        return FieldUtils.readField(rawInjector, "networkManager", true);
    }

    private boolean enableEncryption(SecretKey loginKey) throws IllegalArgumentException {
        // Initialize method reflections
        if (encryptMethod == null) {
            Class<?> networkManagerClass = MinecraftReflection.getNetworkManagerClass();

            try {
                // Try to get the old (pre MC 1.16.4) encryption method
                encryptMethod = FuzzyReflection.fromClass(networkManagerClass)
                        .getMethodByParameters("a", SecretKey.class);
            } catch (IllegalArgumentException exception) {
                // Get the new encryption method
                encryptMethod = FuzzyReflection.fromClass(networkManagerClass)
                        .getMethodByParameters("a", Cipher.class, Cipher.class);

                // Get the needed Cipher helper method (used to generate ciphers from login key)
                Class<?> encryptionClass = MinecraftReflection.getMinecraftClass("MinecraftEncryption");
                cipherMethod = FuzzyReflection.fromClass(encryptionClass)
                        .getMethodByParameters("a", int.class, Key.class);
            }
        }

        try {
            Object networkManager = this.getNetworkManager();

            // If cipherMethod is null - use old encryption (pre MC 1.16.4), otherwise use the new cipher one
            if (cipherMethod == null) {
                // Encrypt/decrypt packet flow, this behaviour is expected by the client
                encryptMethod.invoke(networkManager, loginKey);
            } else {
                // Create ciphers from login key
                Object decryptionCipher = cipherMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
                Object encryptionCipher = cipherMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);

                // Encrypt/decrypt packet flow, this behaviour is expected by the client
                encryptMethod.invoke(networkManager, decryptionCipher, encryptionCipher);
            }
        } catch (Exception ex) {
            disconnect("error-kick", false, "Couldn't enable encryption", ex);
            return false;
        }

        return true;
    }

    private void disconnect(String reasonKey, boolean debug, String logMessage, Object... arguments) {
        if (debug) {
            plugin.getLog().debug(logMessage, arguments);
        } else {
            plugin.getLog().error(logMessage, arguments);
        }

        kickPlayer(plugin.getCore().getMessage(reasonKey));
    }

    private void kickPlayer(String reason) {
        PacketContainer kickPacket = Disconnect.create(WrappedChatComponent.fromText(reason));
        try {
            //send kick packet at login state
            //the normal event.getPlayer.kickPlayer(String) method does only work at play state
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, kickPacket);
            //tell the server that we want to close the connection
            player.kickPlayer("Disconnect");
        } catch (InvocationTargetException ex) {
            plugin.getLog().error("Error sending kick packet for: {}", player, ex);
        }
    }

    //fake a new login packet in order to let the server handle all the other stuff
    private void receiveFakeStartPacket(String username) {
        //see StartPacketListener for packet information
        PacketContainer startPacket = LoginStart.create(username);
        try {
            //we don't want to handle our own packets so ignore filters
            ProtocolLibrary.getProtocolManager().recieveClientPacket(player, startPacket, false);
        } catch (InvocationTargetException | IllegalAccessException ex) {
            plugin.getLog().warn("Failed to fake a new start packet for: {}", username, ex);
            //cancel the event in order to prevent the server receiving an invalid packet
            kickPlayer(plugin.getCore().getMessage("error-kick"));
        }
    }
}
