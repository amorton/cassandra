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
package org.apache.cassandra.db.virtual;

import java.util.Set;

import com.google.common.collect.Sets;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.dht.LocalPartitioner;
import org.apache.cassandra.schema.TableMetadata;

final class SystemPropertiesTable extends AbstractVirtualTable
{
    private static final String NAME = "name";
    private static final String VALUE = "value";

    private static final Set CASSANDRA_RELEVANT_PROPERTIES = Sets.newHashSet(
            // base jvm properties
            "java.class.path",
            "java.home",
            "java.io.tmpdir",
            "java.library.path",
            "java.security.egd",
            "java.version",
            "java.vm.name",
            "line.separator",
            "os.arch",
            "os.name",
            "user.home",
            "sun.arch.data.model",
            // jmx properties
            "java.rmi.server.hostname",
            "com.sun.management.jmxremote.authenticate",
            "com.sun.management.jmxremote.rmi.port",
            "com.sun.management.jmxremote.ssl.need.client.auth",
            "com.sun.management.jmxremote.access.file",
            "com.sun.management.jmxremote.password.file",
            "com.sun.management.jmxremote.port",
            "com.sun.management.jmxremote.ssl.enabled.protocols",
            "com.sun.management.jmxremote.ssl.enabled.cipher.suites",
            "mx4jaddress",
            "mx4jport",
            // cassandra properties (without the "cassandra." prefix)
            "cassandra-foreground",
            "cassandra-pidfile",
            "default.provide.overlapping.tombstones" // why is this not using the "cassandra." prefix ?
            );

    SystemPropertiesTable(String keyspace)
    {
        super(TableMetadata.builder(keyspace, "system_properties")
                           .comment("system properties (relevant to Cassandra)")
                           .kind(TableMetadata.Kind.VIRTUAL)
                           .partitioner(new LocalPartitioner(UTF8Type.instance))
                           .addPartitionKeyColumn(NAME, UTF8Type.instance)
                           .addRegularColumn(VALUE, UTF8Type.instance)
                           .build());
    }

    public DataSet data()
    {
        SimpleDataSet result = new SimpleDataSet(metadata());

        System.getProperties().stringPropertyNames()
                .stream()
                .filter(name -> isCassandraRelevant(name))
                .forEach(name -> addRow(result, name, System.getProperty(name)));

        return result;
    }

    @Override
    public DataSet data(DecoratedKey partitionKey)
    {
        SimpleDataSet result = new SimpleDataSet(metadata());
        String name = UTF8Type.instance.compose(partitionKey.getKey());
        addRow(result, name, isCassandraRelevant(name) ? System.getProperty(name) : null);
        return result;
    }

    private static boolean isCassandraRelevant(String name)
    {
        return name.startsWith(Config.PROPERTY_PREFIX) || CASSANDRA_RELEVANT_PROPERTIES.contains(name);
    }

    private static void addRow(SimpleDataSet result, String name, String value)
    {
        result.row(name).column(VALUE, value);
    }
}
