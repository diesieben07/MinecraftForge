/*
 * Minecraft Forge
 * Copyright (c) 2016-2021.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.network;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSyntaxException;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import static net.minecraftforge.fml.network.FMLNetworkConstants.NETWORK;

/**
 * {
 *      "fmlNetworkVersion" : FMLNETVERSION,
 *      "channels": [
 *          {
 *              "res": "fml:handshake",
 *              "version": "1.2.3.4",
 *              "required": true
 *          }
 *     ],
 *     "mods": [
 *          {
 *              "modid": "modid",
 *              "modmarker": "<somestring>"
 *          }
 *     ]
 * }
 *
 */
public class FMLStatusPing {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int CHANNEL_TRUNCATE_LIMIT = 150;
    private static final int MOD_TRUNCATE_LIMIT = 150;
    private static volatile boolean warnedAboutTruncation = false;

    private transient Map<ResourceLocation, Pair<String, Boolean>> channels;
    private transient Map<String, String> mods;
    private transient int fmlNetworkVer;
    private transient boolean truncated;

    public FMLStatusPing() {
        this.channels = NetworkRegistry.buildChannelVersionsForListPing();
        this.mods = new HashMap<>();
        ModList.get().forEachModContainer((modid, mc) ->
                    mods.put(modid, mc.getCustomExtension(ExtensionPoint.DISPLAYTEST).
                            map(Pair::getLeft).map(Supplier::get).orElse(FMLNetworkConstants.IGNORESERVERONLY)));
        this.fmlNetworkVer = FMLNetworkConstants.FMLNETVERSION;
        this.truncated = false;
    }

    private FMLStatusPing(Map<ResourceLocation, Pair<String, Boolean>> deserialized, Map<String,String> modMarkers, int fmlNetVer, boolean truncated) {
        this.channels = ImmutableMap.copyOf(deserialized);
        this.mods = modMarkers;
        this.fmlNetworkVer = fmlNetVer;
        this.truncated = truncated;
    }

    public static class Serializer {
        public static FMLStatusPing deserialize(JsonObject forgeData, JsonDeserializationContext ctx) {
            try {
                final int remoteFMLVersion = JSONUtils.getAsInt(forgeData, "fmlNetworkVersion");
                final boolean truncated = JSONUtils.getAsBoolean(forgeData, "truncated", false);
                final boolean hasCompressedData = forgeData.has("compr");
                if (truncated && hasCompressedData)
                {
                    return deserializeCompressed(remoteFMLVersion, JSONUtils.getAsString(forgeData, "compr"));
                }
                else
                {
                    final Map<ResourceLocation, Pair<String, Boolean>> channels = StreamSupport.stream(JSONUtils.getAsJsonArray(forgeData, "channels").spliterator(), false).
                            map(JsonElement::getAsJsonObject).
                            collect(Collectors.toMap(jo -> new ResourceLocation(JSONUtils.getAsString(jo, "res")),
                                    jo -> Pair.of(JSONUtils.getAsString(jo, "version"), JSONUtils.getAsBoolean(jo, "required")))
                            );

                    final Map<String, String> mods = StreamSupport.stream(JSONUtils.getAsJsonArray(forgeData, "mods").spliterator(), false).
                            map(JsonElement::getAsJsonObject).
                            collect(Collectors.toMap(jo -> JSONUtils.getAsString(jo, "modId"), jo->JSONUtils.getAsString(jo, "modmarker")));

                    return new FMLStatusPing(channels, mods, remoteFMLVersion, truncated);
                }
            } catch (JsonSyntaxException e) {
                LOGGER.debug(NETWORK, "Encountered an error parsing status ping data", e);
                return null;
            }
        }

        private static void writeChannelList(PacketBuffer buf, FMLStatusPing forgeData, boolean required)
        {
            List<Map.Entry<ResourceLocation, Pair<String, Boolean>>> channels = forgeData.channels.entrySet().stream()
                    .filter(entry -> entry.getValue().getRight() == required)
                    .collect(Collectors.toList());
            buf.writeVarInt(channels.size());
            for (Map.Entry<ResourceLocation, Pair<String, Boolean>> entry : channels)
            {
                buf.writeResourceLocation(entry.getKey());
                buf.writeUtf(entry.getValue().getLeft());
            }
        }

        private static void writeModList(PacketBuffer buf, FMLStatusPing forgeData, boolean ignoreServerOnly)
        {
            // writes mods in two steps, once the ones with IGNORESERVERONLY
            // ones the others
            // this is done because IGNORESERVERONLY is huge and we don't want it on the wire all the time
            List<Map.Entry<String, String>> mods = forgeData.mods.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(FMLNetworkConstants.IGNORESERVERONLY) == ignoreServerOnly)
                    .collect(Collectors.toList());
            buf.writeVarInt(mods.size());
            for (Map.Entry<String, String> entry : mods)
            {
                buf.writeUtf(entry.getKey());
                if (!ignoreServerOnly)
                {
                    buf.writeUtf(entry.getValue());
                }
            }
        }

        public static String serializeCompressed(FMLStatusPing forgeData)
        {
            PacketBuffer buf = new PacketBuffer(Unpooled.buffer());

            // first write the channels
            writeChannelList(buf, forgeData, true);
            writeChannelList(buf, forgeData, false);

            // now write the mods
            writeModList(buf, forgeData, true);
            writeModList(buf, forgeData, false);


            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private static void readChannelList(PacketBuffer buf, boolean required, Map<ResourceLocation, Pair<String, Boolean>> target)
        {
            int length = buf.readVarInt();
            for (int i = 0; i < length; i++)
            {
                ResourceLocation channelName = buf.readResourceLocation();
                String channelVersion = buf.readUtf();
                target.put(channelName, Pair.of(channelVersion, required));
            }
        }

        private static void readModsList(PacketBuffer buf, boolean ignoreServerOnly, Map<String, String> target)
        {
            // see writeModList
            int length = buf.readVarInt();
            for (int i = 0; i < length; i++)
            {
                String modId = buf.readUtf();
                String modVersion;
                if (ignoreServerOnly)
                {
                    modVersion = FMLNetworkConstants.IGNORESERVERONLY;
                }
                else
                {
                    modVersion = buf.readUtf();
                }
                target.put(modId, modVersion);
            }
        }

        public static FMLStatusPing deserializeCompressed(int fmlNetVersion, String data)
        {
            byte[] bytes = Base64.getDecoder().decode(data);
            PacketBuffer buf = new PacketBuffer(Unpooled.wrappedBuffer(bytes));

            Map<ResourceLocation, Pair<String, Boolean>> channels = new HashMap<>();
            Map<String, String> mods = new HashMap<>();
            readChannelList(buf, true, channels);
            readChannelList(buf, false, channels);

            readModsList(buf, true, mods);
            readModsList(buf, false, mods);

            return new FMLStatusPing(channels, mods, fmlNetVersion, false);
        }

        public static JsonObject serialize(FMLStatusPing forgeData, JsonSerializationContext ctx) {
            boolean truncated = forgeData.channels.size() > CHANNEL_TRUNCATE_LIMIT || forgeData.mods.size() > MOD_TRUNCATE_LIMIT;
            if (truncated && !warnedAboutTruncation)
            {
                warnedAboutTruncation = true;
                LOGGER.warn("Heuristically truncating mod and/or network channel list in server status ping packet. Compatibility report on older " +
                        "Minecraft clients or external services may be inaccurate.");
            }

            JsonObject obj = new JsonObject();
            JsonArray channels = new JsonArray();
            JsonArray modTestValues = new JsonArray();

            if (truncated)
            {
                obj.addProperty("compr", serializeCompressed(forgeData));
            } else
            {
                forgeData.channels.forEach((namespace, version) -> {
                    JsonObject mi = new JsonObject();
                    mi.addProperty("res", namespace.toString());
                    mi.addProperty("version", version.getLeft());
                    mi.addProperty("required", version.getRight());
                    channels.add(mi);
                });
                forgeData.mods.forEach((modId, value) -> {
                    JsonObject mi = new JsonObject();
                    mi.addProperty("modId", modId);
                    mi.addProperty("modmarker", value);
                    modTestValues.add(mi);
                });
            }

            obj.add("channels", channels);
            obj.add("mods", modTestValues);
            obj.addProperty("fmlNetworkVersion", forgeData.fmlNetworkVer);
            obj.addProperty("truncated", truncated);
            return obj;
        }
    }

    public Map<ResourceLocation, Pair<String, Boolean>> getRemoteChannels() {
        return this.channels;
    }

    public Map<String,String> getRemoteModData() {
        return mods;
    }

    public int getFMLNetworkVersion() {
        return fmlNetworkVer;
    }

    public boolean isTruncated()
    {
        return truncated;
    }
}
