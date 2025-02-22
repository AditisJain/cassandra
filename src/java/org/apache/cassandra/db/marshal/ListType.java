/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.cassandra.cql3.Json;
import org.apache.cassandra.cql3.Lists;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.serializers.CollectionSerializer;
import org.apache.cassandra.serializers.ListSerializer;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.bytecomparable.ByteComparable.Version;
import org.apache.cassandra.utils.bytecomparable.ByteSource;
import org.apache.cassandra.utils.bytecomparable.ByteSourceInverse;

public class ListType<T> extends CollectionType<List<T>>
{
    // interning instances
    private static final ConcurrentHashMap<AbstractType<?>, ListType> instances = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<AbstractType<?>, ListType> frozenInstances = new ConcurrentHashMap<>();

    private final AbstractType<T> elements;
    public final ListSerializer<T> serializer;
    private final boolean isMultiCell;

    public static ListType<?> getInstance(TypeParser parser) throws ConfigurationException, SyntaxException
    {
        List<AbstractType<?>> l = parser.getTypeParameters();
        if (l.size() != 1)
            throw new ConfigurationException("ListType takes exactly 1 type parameter");

        return getInstance(l.get(0), true);
    }

    public static <T> ListType<T> getInstance(AbstractType<T> elements, boolean isMultiCell)
    {
        ConcurrentHashMap<AbstractType<?>, ListType> internMap = isMultiCell ? instances : frozenInstances;
        ListType<T> t = internMap.get(elements);
        return null == t
             ? internMap.computeIfAbsent(elements, k -> new ListType<>(k, isMultiCell))
             : t;
    }

    @Override
    public ListType<T> overrideKeyspace(Function<String, String> overrideKeyspace)
    {
        AbstractType<T> newType = elements.overrideKeyspace(overrideKeyspace);
        if (newType == elements)
            return this;

        return getInstance(newType, isMultiCell());
    }

    private ListType(AbstractType<T> elements, boolean isMultiCell)
    {
        super(ComparisonType.CUSTOM, Kind.LIST);
        this.elements = elements;
        this.serializer = ListSerializer.getInstance(elements.getSerializer());
        this.isMultiCell = isMultiCell;
    }

    @Override
    public <V> boolean referencesUserType(V name, ValueAccessor<V> accessor)
    {
        return elements.referencesUserType(name, accessor);
    }

    @Override
    public ListType<?> withUpdatedUserType(UserType udt)
    {
        if (!referencesUserType(udt.name))
            return this;

        (isMultiCell ? instances : frozenInstances).remove(elements);

        return getInstance(elements.withUpdatedUserType(udt), isMultiCell);
    }

    @Override
    public AbstractType<?> expandUserTypes()
    {
        return getInstance(elements.expandUserTypes(), isMultiCell);
    }

    @Override
    public boolean referencesDuration()
    {
        return getElementsType().referencesDuration();
    }

    public AbstractType<T> getElementsType()
    {
        return elements;
    }

    public AbstractType<UUID> nameComparator()
    {
        return TimeUUIDType.instance;
    }

    public AbstractType<T> valueComparator()
    {
        return elements;
    }

    public ListSerializer<T> getSerializer()
    {
        return serializer;
    }

    @Override
    public AbstractType<?> freeze()
    {
        if (isMultiCell)
            return getInstance(this.elements, false);
        else
            return this;
    }

    @Override
    public AbstractType<?> freezeNestedMulticellTypes()
    {
        if (!isMultiCell())
            return this;

        if (elements.isFreezable() && elements.isMultiCell())
            return getInstance(elements.freeze(), isMultiCell);

        return getInstance(elements.freezeNestedMulticellTypes(), isMultiCell);
    }

    @Override
    public List<AbstractType<?>> subTypes()
    {
        return Collections.singletonList(elements);
    }

    @Override
    public boolean isMultiCell()
    {
        return isMultiCell;
    }

    @Override
    public boolean isCompatibleWithFrozen(CollectionType<?> previous)
    {
        assert !isMultiCell;
        return this.elements.isCompatibleWith(((ListType) previous).elements);
    }

    @Override
    public boolean isValueCompatibleWithFrozen(CollectionType<?> previous)
    {
        assert !isMultiCell;
        return this.elements.isValueCompatibleWithInternal(((ListType) previous).elements);
    }

    public <VL, VR> int compareCustom(VL left, ValueAccessor<VL> accessorL, VR right, ValueAccessor<VR> accessorR)
    {
        return compareListOrSet(elements, left, accessorL, right, accessorR);
    }

    static <VL, VR> int compareListOrSet(AbstractType<?> elementsComparator, VL left, ValueAccessor<VL> accessorL, VR right, ValueAccessor<VR> accessorR)
    {
        // Note that this is only used if the collection is frozen
        if (accessorL.isEmpty(left) || accessorR.isEmpty(right))
            return Boolean.compare(accessorR.isEmpty(right), accessorL.isEmpty(left));

        int sizeL = CollectionSerializer.readCollectionSize(left, accessorL, ProtocolVersion.V3);
        int offsetL = CollectionSerializer.sizeOfCollectionSize(sizeL, ProtocolVersion.V3);
        int sizeR = CollectionSerializer.readCollectionSize(right, accessorR, ProtocolVersion.V3);
        int offsetR = TypeSizes.INT_SIZE;

        for (int i = 0; i < Math.min(sizeL, sizeR); i++)
        {
            VL v1 = CollectionSerializer.readValue(left, accessorL, offsetL, ProtocolVersion.V3);
            offsetL += CollectionSerializer.sizeOfValue(v1, accessorL, ProtocolVersion.V3);
            VR v2 = CollectionSerializer.readValue(right, accessorR, offsetR, ProtocolVersion.V3);
            offsetR += CollectionSerializer.sizeOfValue(v2, accessorR, ProtocolVersion.V3);
            int cmp = elementsComparator.compare(v1, accessorL, v2, accessorR);
            if (cmp != 0)
                return cmp;
        }

        return sizeL == sizeR ? 0 : (sizeL < sizeR ? -1 : 1);
    }

    @Override
    public <V> ByteSource asComparableBytes(ValueAccessor<V> accessor, V data, Version version)
    {
        return asComparableBytesListOrSet(getElementsType(), accessor, data, version);
    }

    @Override
    public <V> V fromComparableBytes(ValueAccessor<V> accessor, ByteSource.Peekable comparableBytes, Version version)
    {
        return fromComparableBytesListOrSet(accessor, comparableBytes, version, getElementsType());
    }

    static <V> ByteSource asComparableBytesListOrSet(AbstractType<?> elementsComparator,
                                                     ValueAccessor<V> accessor,
                                                     V data,
                                                     Version version)
    {
        if (accessor.isEmpty(data))
            return null;

        int offset = 0;
        int size = CollectionSerializer.readCollectionSize(data, accessor, ProtocolVersion.V3);
        offset += CollectionSerializer.sizeOfCollectionSize(size, ProtocolVersion.V3);
        ByteSource[] srcs = new ByteSource[size];
        for (int i = 0; i < size; ++i)
        {
            V v = CollectionSerializer.readValue(data, accessor, offset, ProtocolVersion.V3);
            offset += CollectionSerializer.sizeOfValue(v, accessor, ProtocolVersion.V3);
            srcs[i] = elementsComparator.asComparableBytes(accessor, v, version);
        }
        return ByteSource.withTerminator(version == Version.LEGACY ? 0x00 : ByteSource.TERMINATOR, srcs);
    }

    static <V> V fromComparableBytesListOrSet(ValueAccessor<V> accessor,
                                              ByteSource.Peekable comparableBytes,
                                              Version version,
                                              AbstractType<?> elementType)
    {
        if (comparableBytes == null)
            return accessor.empty();

        List<V> buffers = new ArrayList<>();
        int terminator = version == Version.LEGACY
                         ? 0x00
                         : ByteSource.TERMINATOR;
        int separator = comparableBytes.next();
        while (separator != terminator)
        {
            if (!ByteSourceInverse.nextComponentNull(separator))
                buffers.add(elementType.fromComparableBytes(accessor, comparableBytes, version));
            else
                buffers.add(null);
            separator = comparableBytes.next();
        }
        return CollectionSerializer.pack(buffers, accessor, buffers.size(), ProtocolVersion.V3);
    }

    @Override
    public String toString(boolean ignoreFreezing)
    {
        boolean includeFrozenType = !ignoreFreezing && !isMultiCell();

        StringBuilder sb = new StringBuilder();
        if (includeFrozenType)
            sb.append(FrozenType.class.getName()).append("(");
        sb.append(getClass().getName());
        sb.append(TypeParser.stringifyTypeParameters(Collections.<AbstractType<?>>singletonList(elements), ignoreFreezing || !isMultiCell));
        if (includeFrozenType)
            sb.append(")");
        return sb.toString();
    }

    public List<ByteBuffer> serializedValues(Iterator<Cell<?>> cells)
    {
        assert isMultiCell;
        List<ByteBuffer> bbs = new ArrayList<ByteBuffer>();
        while (cells.hasNext())
            bbs.add(cells.next().buffer());
        return bbs;
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
        if (parsed instanceof String)
            parsed = Json.decodeJson((String) parsed);

        if (!(parsed instanceof List))
            throw new MarshalException(String.format(
                    "Expected a list, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

        List list = (List) parsed;
        List<Term> terms = new ArrayList<>(list.size());
        for (Object element : list)
        {
            if (element == null)
                throw new MarshalException("Invalid null element in list");
            terms.add(elements.fromJSONObject(element));
        }

        return new Lists.DelayedValue(terms);
    }

    public static String setOrListToJsonString(ByteBuffer buffer, AbstractType elementsType, ProtocolVersion protocolVersion)
    {
        ByteBuffer value = buffer.duplicate();
        StringBuilder sb = new StringBuilder("[");
        int size = CollectionSerializer.readCollectionSize(value, ByteBufferAccessor.instance, protocolVersion);
        int offset = CollectionSerializer.sizeOfCollectionSize(size, protocolVersion);
        for (int i = 0; i < size; i++)
        {
            if (i > 0)
                sb.append(", ");
            ByteBuffer element = CollectionSerializer.readValue(value, ByteBufferAccessor.instance, offset, protocolVersion);
            offset += CollectionSerializer.sizeOfValue(element, ByteBufferAccessor.instance, protocolVersion);
            sb.append(elementsType.toJSONString(element, protocolVersion));
        }
        return sb.append("]").toString();
    }

    public ByteBuffer getSliceFromSerialized(ByteBuffer collection, ByteBuffer from, ByteBuffer to)
    {
        // We don't support slicing on lists so we don't need that function
        throw new UnsupportedOperationException();
    }

    @Override
    public String toJSONString(ByteBuffer buffer, ProtocolVersion protocolVersion)
    {
        return setOrListToJsonString(buffer, elements, protocolVersion);
    }
}
