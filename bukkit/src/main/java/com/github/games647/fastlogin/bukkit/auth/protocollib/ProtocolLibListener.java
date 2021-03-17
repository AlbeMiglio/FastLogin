package com.github.games647.fastlogin.bukkit.auth.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import com.github.games647.fastlogin.bukkit.auth.protocollib.packet.IncomingPacket.EncryptionReply;
import com.github.games647.fastlogin.bukkit.auth.protocollib.packet.IncomingPacket.LoginStart;
import com.github.games647.fastlogin.core.auth.RateLimiter;

import io.papermc.lib.PaperLib;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.SecureRandom;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;

import static com.comphenix.protocol.PacketType.Login.Client.ENCRYPTION_BEGIN;
import static com.comphenix.protocol.PacketType.Login.Client.START;

public class ProtocolLibListener extends PacketAdapter {

    private final FastLoginBukkit plugin;

    //just create a new once on plugin enable. This used for verify token generation
    private final SecureRandom random = new SecureRandom();
    private final KeyPair keyPair = EncryptionUtil.generateKeyPair();
    private final RateLimiter rateLimiter;

    // Wait before the server is fully started. This is workaround, because connections right on startup are not
    // injected by ProtocolLib
    private boolean serverStarted;

    protected ProtocolLibListener(FastLoginBukkit plugin, RateLimiter rateLimiter) {
        //run async in order to not block the server, because we are making api calls to Mojang
        super(params()
                .plugin(plugin)
                .types(START, ENCRYPTION_BEGIN)
                .optionAsync());

        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
    }

    public static void register(FastLoginBukkit plugin, RateLimiter rateLimiter) {
        //they will be created with a static builder, because otherwise it will throw a NoClassDefFoundError
        ProtocolLibListener packetListener = new ProtocolLibListener(plugin, rateLimiter);
        ProtocolLibrary.getProtocolManager()
                .getAsynchronousManager()
                .registerAsyncHandler(packetListener)
                .start();

        PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        pluginManager.registerEvents(new InitializedListener(packetListener), plugin);

        //if server is using paper - we need to set the skin at pre login anyway, so no need for this listener
        if (!PaperLib.isPaper() && plugin.getConfig().getBoolean("forwardSkin")) {
            pluginManager.registerEvents(new SkinApplyListener(plugin), plugin);
        }
    }

    @Override
    public void onPacketReceiving(PacketEvent packetEvent) {
        if (packetEvent.isCancelled() || plugin.getCore().getAuthPluginHook() == null) {
            return;
        }

        markReadyToInject();

        Player sender = packetEvent.getPlayer();
        PacketType packetType = packetEvent.getPacketType();
        if (packetType == START) {
            if (!rateLimiter.tryAcquire()) {
                plugin.getLog().warn("Join limit hit - Ignoring player {}", sender);
                return;
            }

            LoginStart packet = LoginStart.from(packetEvent.getPacket());
            onLogin(packetEvent, packet, sender);
        } else if (packetType == ENCRYPTION_BEGIN) {
            EncryptionReply packet = EncryptionReply.from(packetEvent.getPacket());
            onEncryptionBegin(packetEvent, packet, sender);
        }
    }

    private void onEncryptionBegin(PacketEvent packetEvent, EncryptionReply packet, Player sender) {
        packetEvent.getAsyncMarker().incrementProcessingDelay();
        Runnable verifyTask = new VerifyResponseTask(plugin, packetEvent, sender, packet, keyPair);
        plugin.getScheduler().runAsync(verifyTask);
    }

    private void onLogin(PacketEvent packetEvent, LoginStart packet, Player player) {
        //this includes ip:port. Should be unique for an incoming login request with a timeout of 2 minutes
        InetSocketAddress address = player.getAddress();

        //remove old data every time on a new login in order to keep the session only for one person
        plugin.getSessionManager().endLoginSession(address);

        String username = packet.getUsername();
        plugin.getLog().trace("GameProfile {} with {} connecting", address, username);

        packetEvent.getAsyncMarker().incrementProcessingDelay();
        Runnable nameCheckTask = new NameCheckTask(plugin, packetEvent, random, player, username, keyPair.getPublic());
        plugin.getScheduler().runAsync(nameCheckTask);
    }

    public void markReadyToInject() {
        this.serverStarted = true;
    }

    public boolean isReadyToInject() {
        return serverStarted;
    }

    @Override
    public FastLoginBukkit getPlugin() {
        return plugin;
    }
}
