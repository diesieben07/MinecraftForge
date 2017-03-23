/*
 * Minecraft Forge
 * Copyright (c) 2016.
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

package net.minecraftforge.fml.common.registry;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.BiMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Main interface for the registry system. Use this to query the registry system.
 *
 * @param <V> The top level type for the registry
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public interface IForgeRegistry<V extends IForgeRegistryEntry<V>> extends Iterable<V>
{
    public Class<V> getRegistrySuperType();

    void register(V value);

    void registerAll(V... values);

    boolean containsKey(ResourceLocation key);
    boolean containsValue(V value);

    @Nullable V getValue(ResourceLocation key);
    @Nullable ResourceLocation getKey(V value);

    @Nonnull Set<ResourceLocation>           getKeys();
    @Nonnull List<V>                         getValues();
    @Nonnull Set<Entry<ResourceLocation, V>> getEntries();

    /**
     * Retrieve the slave map of type T from the registry.
     * Slave maps are maps which are dependent on registry content in some way.
     * @param slaveMapName The name of the slavemap
     * @param type The type
     * @param <T> Type to return
     * @return The slavemap if present
     */
    <T> T getSlaveMap(ResourceLocation slaveMapName, Class<T> type);

    /**
     * Write a registry entry to the stream. The serialized format is not specified and must not be relied upon.
     * Do not use this to write to a file, it is used for client-server communication only.
     * @param out the buffer to write to
     * @param entry the registry entry
     */
    void writeEntry(ByteBuf out, V entry);

    /**
     * Read a registry entry from the stream. The same format as in {@link #writeEntry(ByteBuf, IForgeRegistryEntry)} is used.
     * @param in the buffer to read from
     * @return the read registry entry
     */
    V readEntry(ByteBuf in);

    /**
     * Callback fired when objects are added to the registry. This will fire when the registry is rebuilt
     * on the client side from a server side synchronization, or when a world is loaded.
     */
    interface AddCallback<V extends IForgeRegistryEntry<V>>
    {
        void onAdd(V obj, int id, Map<ResourceLocation, ?> slaveset);
    }

    /**
     * Callback fired when the registry is cleared. This is done before a registry is reloaded from client
     * or server.
     */
    interface ClearCallback<V extends IForgeRegistryEntry<V>>
    {
        void onClear(IForgeRegistry<V> is, Map<ResourceLocation, ?> slaveset);
    }

    /**
     * Callback fired when a registry instance is created. Populate slave maps here.
     */
    interface CreateCallback<V extends IForgeRegistryEntry<V>>
    {
        void onCreate(Map<ResourceLocation, ?> slaveset, BiMap<ResourceLocation, ? extends IForgeRegistry<?>> registries);
    }

    interface SubstitutionCallback<V extends IForgeRegistryEntry<V>>
    {
        void onSubstituteActivated(Map<ResourceLocation, ?> slaveset, V original, V replacement, ResourceLocation name);
    }
}
