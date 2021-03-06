/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.cursor;

import java.util.Iterator;

import org.neo4j.function.Consumer;
import org.neo4j.kernel.api.cursor.PropertyCursor;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.util.VersionedHashMap;

/**
 * Overlays transaction state on a {@link PropertyCursor}.
 */
public class TxPropertyCursor implements PropertyCursor
{
    private final Consumer<TxPropertyCursor> instanceCache;

    private PropertyCursor cursor;
    private VersionedHashMap<Integer, DefinedProperty> addedProperties;
    private VersionedHashMap<Integer, DefinedProperty> changedProperties;
    private VersionedHashMap<Integer, DefinedProperty> removedProperties;

    private DefinedProperty property;
    private Iterator<DefinedProperty> added;


    public TxPropertyCursor( Consumer<TxPropertyCursor> instanceCache )
    {
        this.instanceCache = instanceCache;
    }

    public PropertyCursor init( PropertyCursor cursor,
            VersionedHashMap<Integer, DefinedProperty> addedProperties,
            VersionedHashMap<Integer, DefinedProperty> changedProperties,
            VersionedHashMap<Integer, DefinedProperty> removedProperties )
    {
        this.cursor = cursor;
        this.addedProperties = addedProperties;
        this.changedProperties = changedProperties;
        this.removedProperties = removedProperties;

        return this;
    }

    @Override
    public boolean next()
    {
        if ( added == null )
        {
            while ( cursor.next() )
            {
                if ( changedProperties != null )
                {
                    Property property = changedProperties.get( cursor.getProperty().propertyKeyId() );

                    if ( property != null )
                    {
                        this.property = (DefinedProperty) property;
                        return true;
                    }
                }

                if ( removedProperties == null || !removedProperties.containsKey(
                        cursor.getProperty().propertyKeyId() ) )
                {
                    this.property = cursor.getProperty();
                    return true;
                }
            }

            if ( addedProperties != null )
            {
                added = addedProperties.values().iterator();
            }

        }

        if ( added != null && added.hasNext() )
        {
            property = added.next();
            return true;
        }
        else
        {
            property = null;
            return false;
        }
    }

    @Override
    public boolean seek( int keyId )
    {
        if ( changedProperties != null )
        {
            Property property = changedProperties.get( keyId );

            if ( property != null )
            {
                this.property = (DefinedProperty) property;
                return true;
            }
        }

        if ( addedProperties != null )
        {
            Property property = addedProperties.get( keyId );

            if ( property != null )
            {
                this.property = (DefinedProperty) property;
                return true;
            }
        }

        if ( removedProperties != null && removedProperties.containsKey( keyId ) )
        {
            this.property = null;
            return false;
        }

        if ( cursor.seek( keyId ) )
        {
            this.property = cursor.getProperty();
            return true;
        }
        else
        {
            this.property = null;
            return false;
        }
    }

    @Override
    public DefinedProperty getProperty()
    {
        if (property == null)
            throw new IllegalStateException(  );

        return property;
    }

    @Override
    public void close()
    {
        cursor.close();
        cursor = null;
        this.added = null;
        instanceCache.accept( this );
    }
}
