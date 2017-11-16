/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.cassandra.locator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.OrderPreservingPartitioner.StringToken;
import org.apache.cassandra.dht.Token;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

public class EachDatacenterStrategyTest
{
    private String keyspaceName = "Keyspace1";
    private static final Logger logger = LoggerFactory.getLogger(EachDatacenterStrategyTest.class);

    @Test
    public void testWithThreeDcs() throws UnknownHostException, ConfigurationException
    {
        int[] dcRacks = new int[] { 2, 4, 8 };
        int[] dcEndpoints = new int[] { 128, 256, 512 };
        int[] dcReplication = new int[] { 3, 3, 3 };

        IEndpointSnitch snitch = new RackInferringSnitch();
        DatabaseDescriptor.setEndpointSnitch(snitch);
        TokenMetadata metadata = new TokenMetadata();
        Map<String, String> configOptions = new HashMap<String, String>();
        Multimap<InetAddress, Token> tokens = HashMultimap.create();

        configOptions.put(EachDatacenterStrategy.REPLICATION_FACTOR_STRING, "3");

        int totalRF = 0;
        for (int dc = 0; dc < dcRacks.length; ++dc)
        {
            totalRF += dcReplication[dc];
            for (int rack = 0; rack < dcRacks[dc]; ++rack)
            {
                for (int ep = 1; ep <= dcEndpoints[dc] / dcRacks[dc]; ++ep)
                {
                    byte[] ipBytes = new byte[] { 10, (byte) dc, (byte) rack, (byte) ep };
                    InetAddress address = InetAddress.getByAddress(ipBytes);
                    StringToken token = new StringToken(String.format("%02x%02x%02x", ep, rack, dc));
                    logger.debug("adding node {} at {}", address, token);
                    tokens.put(address, token);
                }
            }
        }
        metadata.updateNormalTokens(tokens);


        EachDatacenterStrategy strategy = new EachDatacenterStrategy(keyspaceName, metadata, snitch, configOptions);
        assert strategy.getReplicationFactor("0") == 3;
        assert strategy.getReplicationFactor("1") == 3;
        assert strategy.getReplicationFactor("2") == 3;

        for (String testToken : new String[] { "123456", "200000", "000402", "ffffff", "400200" })
        {
            List<InetAddress> endpoints = strategy.calculateNaturalEndpoints(new StringToken(testToken), metadata);
            Set<InetAddress> epSet = new HashSet<InetAddress>(endpoints);

            Assert.assertEquals(totalRF, endpoints.size());
            Assert.assertEquals(totalRF, epSet.size());
            logger.debug("{}: {}", testToken, endpoints);
        }
    }

    @Test
    public void testWithExclusion() throws UnknownHostException, ConfigurationException
    {
        int[] dcRacks = new int[]{2, 4, 8};
        int[] dcEndpoints = new int[]{128, 256, 512};

        IEndpointSnitch snitch = new RackInferringSnitch();
        DatabaseDescriptor.setEndpointSnitch(snitch);
        TokenMetadata metadata = new TokenMetadata();
        Map<String, String> configOptions = new HashMap<String, String>();
        Multimap<InetAddress, Token> tokens = HashMultimap.create();

        configOptions.put(EachDatacenterStrategy.REPLICATION_FACTOR_STRING, "3");
        configOptions.put(EachDatacenterStrategy.EXCLUDED_DATACENTERS_STRING, "0 , 1");

        for (int dc = 0; dc < dcRacks.length; ++dc)
        {
            for (int rack = 0; rack < dcRacks[dc]; ++rack)
            {
                for (int ep = 1; ep <= dcEndpoints[dc]/dcRacks[dc]; ++ep)
                {
                    byte[] ipBytes = new byte[]{10, (byte)dc, (byte)rack, (byte)ep};
                    InetAddress address = InetAddress.getByAddress(ipBytes);
                    StringToken token = new StringToken(String.format("%02x%02x%02x", ep, rack, dc));
                    logger.debug("adding node {} at {}", address, token);
                    tokens.put(address, token);
                }
            }
        }
        metadata.updateNormalTokens(tokens);


        EachDatacenterStrategy strategy = new EachDatacenterStrategy(keyspaceName, metadata, snitch, configOptions);
        assert strategy.getReplicationFactor("0") == 0;
        assert strategy.getReplicationFactor("1") == 0;
        assert strategy.getReplicationFactor("2") == 3;

        for (String testToken : new String[]{"123456", "200000", "000402", "ffffff", "400200"})
        {
            List<InetAddress> endpoints = strategy.calculateNaturalEndpoints(new StringToken(testToken), metadata);
            Set<InetAddress> epSet = new HashSet<InetAddress>(endpoints);

            Assert.assertEquals(3, endpoints.size());
            Assert.assertEquals(3, epSet.size());
            logger.debug("{}: {}", testToken, endpoints);
        }
    }

    @Test(expected = NumberFormatException.class)
    public void testMissingOption() throws UnknownHostException, ConfigurationException
    {
        int[] dcRacks = new int[] { 2, 4, 8 };
        int[] dcEndpoints = new int[] { 128, 256, 512 };

        IEndpointSnitch snitch = new RackInferringSnitch();
        DatabaseDescriptor.setEndpointSnitch(snitch);
        TokenMetadata metadata = new TokenMetadata();
        Map<String, String> configOptions = new HashMap<String, String>();
        Multimap<InetAddress, Token> tokens = HashMultimap.create();

        configOptions.put(EachDatacenterStrategy.EXCLUDED_DATACENTERS_STRING, "0 , 1");

        for (int dc = 0; dc < dcRacks.length; ++dc)
        {
            for (int rack = 0; rack < dcRacks[dc]; ++rack)
            {
                for (int ep = 1; ep <= dcEndpoints[dc] / dcRacks[dc]; ++ep)
                {
                    byte[] ipBytes = new byte[] { 10, (byte) dc, (byte) rack, (byte) ep };
                    InetAddress address = InetAddress.getByAddress(ipBytes);
                    StringToken token = new StringToken(String.format("%02x%02x%02x", ep, rack, dc));
                    logger.debug("adding node {} at {}", address, token);
                    tokens.put(address, token);
                }
            }
        }
        metadata.updateNormalTokens(tokens);

        EachDatacenterStrategy strategy = new EachDatacenterStrategy(keyspaceName, metadata, snitch, configOptions);
    }

}
