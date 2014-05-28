/**
 * Copyright © 2013 tuxed <write@imaginarycode.com>
 * This work is free. You can redistribute it and/or modify it under the
 * terms of the Do What The Fuck You Want To Public License, Version 2,
 * as published by Sam Hocevar. See http://www.wtfpl.net/ for more details.
 */
package com.imaginarycode.minecraft.redisbungee.util;

import com.google.common.base.Charsets;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ProxyServer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class UUIDTranslator {
    private final RedisBungee plugin;
    private BiMap<String, UUID> uuidMap = Maps.synchronizedBiMap(HashBiMap.<String, UUID>create());
    public static final Pattern UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    public static final Pattern MOJANGIAN_UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    public UUID getTranslatedUuid(@NonNull String player) {
        if (ProxyServer.getInstance().getPlayer(player) != null)
            return ProxyServer.getInstance().getPlayer(player).getUniqueId();

        UUID uuid = uuidMap.get(player);
        if (uuid != null)
            return uuid;

        if (UUID_PATTERN.matcher(player).find()) {
            return UUID.fromString(player);
        }

        if (MOJANGIAN_UUID_PATTERN.matcher(player).find()) {
            // Reconstruct the UUID
            return UUIDFetcher.getUUID(player);
        }

        if (!plugin.getProxy().getConfig().isOnlineMode()) {
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(Charsets.UTF_8));
            uuidMap.put(player, uuid);
            return uuid;
        }

        // Okay, it wasn't locally cached. Let's try Redis.
        Jedis jedis = plugin.getPool().getResource();
        try {
            try {
                String stored = jedis.hget("uuids", player.toLowerCase());
                if (stored != null && UUID_PATTERN.matcher(stored).find()) {
                    // This is it!
                    uuid = UUID.fromString(stored);
                    storeInfo(player, uuid, jedis);
                    uuidMap.put(player, uuid);
                    return uuid;
                }

                // That didn't work. Let's ask Mojang.
                Map<String, UUID> uuidMap1;
                try {
                    uuidMap1 = new UUIDFetcher(Collections.singletonList(player)).call();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Unable to fetch UUID from Mojang for " + player, e);
                    return null;
                }
                for (Map.Entry<String, UUID> entry : uuidMap1.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(player)) {
                        uuidMap.put(player, entry.getValue());
                        storeInfo(player, entry.getValue(), jedis);
                        return entry.getValue();
                    }
                }
            } catch (JedisException e) {
                plugin.getLogger().log(Level.SEVERE, "Unable to fetch UUID for " + player, e);
                // Go ahead and give them what we have.
                return uuid;
            }
        } finally {
            plugin.getPool().returnResource(jedis);
        }

        return null; // Nope, game over!
    }

    public String getNameFromUuid(@NonNull UUID player) {
        if (ProxyServer.getInstance().getPlayer(player) != null)
            return ProxyServer.getInstance().getPlayer(player).getName();

        String name = uuidMap.inverse().get(player);

        if (name != null)
            return name;

        // Okay, it wasn't locally cached. Let's try Redis.
        Jedis jedis = plugin.getPool().getResource();
        try {
            String stored = jedis.hget("player:" + player, "name");
            if (stored != null) {
                name = stored;
                uuidMap.put(name, player);
                return name;
            }

            // That didn't work. Let's ask Mojang.
            try {
                name = new NameFetcher(Collections.singletonList(player)).call().get(player);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Unable to fetch name from Mojang for " + player, e);
                return null;
            }

            if (name != null) {
                storeInfo(name, player, jedis);
                return name;
            }

            return null;
        } catch (JedisException e) {
            plugin.getLogger().log(Level.SEVERE, "Unable to fetch name for " + player, e);
            return name;
        } finally {
            plugin.getPool().returnResource(jedis);
        }
    }

    private static void storeInfo(String name, UUID uuid, Jedis jedis) {
        jedis.hset("uuids", name.toLowerCase(), uuid.toString());
        jedis.hset("player:" + uuid, "name", name);
    }
}
