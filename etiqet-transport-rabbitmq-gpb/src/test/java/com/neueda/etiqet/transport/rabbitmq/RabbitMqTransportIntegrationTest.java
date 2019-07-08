package com.neueda.etiqet.transport.rabbitmq;

import com.neueda.etiqet.core.json.JsonCodec;
import com.neueda.etiqet.core.message.cdr.Cdr;
import com.neueda.etiqet.core.message.dictionary.ProtobufDictionary;
import com.neueda.etiqet.core.transport.Codec;
import com.neueda.etiqet.core.transport.ExchangeTransport;
import com.neueda.etiqet.core.transport.ProtobufCodec;
import com.neueda.etiqet.rabbitmq.embeddedBroker.EmbeddedQpidBrokerRule;
import com.neueda.etiqet.transport.rabbitmq.config.AmqpConfigExtractor;
import com.neueda.etiqet.transport.rabbitmq.config.model.AmqpConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static com.neueda.etiqet.core.message.CdrBuilder.aCdr;
import static com.neueda.etiqet.transport.rabbitmq.config.model.AmqpConfigBuilder.aAmqpConfig;
import static com.neueda.etiqet.transport.rabbitmq.config.model.ExchangeConfigBuilder.aExchangeConfig;
import static com.neueda.etiqet.transport.rabbitmq.config.model.QueueConfigBuilder.aQueueConfig;
import static com.rabbitmq.client.BuiltinExchangeType.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RabbitMqTransportIntegrationTest {
    private List<ExchangeTransport> transports;

    @Rule
    public EmbeddedQpidBrokerRule qpidBrokerRule = new EmbeddedQpidBrokerRule();

    @Before
    public void setup() {
        transports = new ArrayList<>();
    }

    @After
    public void tearDown() {
        transports.forEach(ExchangeTransport::stop);
    }

    @Test
    public void testFanout() throws Exception {
        final String exchangeName = "exchangeFanout";
        ExchangeTransport producerTransport = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, FANOUT)
                .build()
            ).build()
        );
        ExchangeTransport consumerTransport1 = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, FANOUT)
                .addQueueConfig(
                    aQueueConfig("queue1").build()
                ).build()
            ).build()
        );
        ExchangeTransport consumerTransport2 = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, FANOUT)
                .addQueueConfig(
                    aQueueConfig("queue2").build()
                ).build()
            ).build()
        );
        Cdr cdrRequest = aCdr("NONE").withField("field1", "value1").build();
        BlockingQueue<Cdr> cdrMessages1 = new LinkedBlockingQueue<>();
        BlockingQueue<Cdr> cdrMessages2 = new LinkedBlockingQueue<>();

        consumerTransport1.subscribeToQueue("queue1", cdr -> cdrMessages1.add(cdr));
        consumerTransport2.subscribeToQueue("queue2", cdr -> cdrMessages2.add(cdr));
        producerTransport.sendToExchange(cdrRequest, exchangeName);

        assertEquals("value1", cdrMessages1.poll(2, SECONDS).getAsString("field1"));
        assertEquals("value1", cdrMessages2.poll(2, SECONDS).getAsString("field1"));
        assertEquals(0, cdrMessages1.size());
        assertEquals(0, cdrMessages2.size());
    }

    @Test
    public void testFanout_onSingleTransport() throws Exception {
        final String exchangeName = "exchangeFanoutOneTransport";
        ExchangeTransport transport = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, FANOUT)
                    .addQueueConfig(
                        aQueueConfig("queue1").build()
                    ).addQueueConfig(
                        aQueueConfig("queue2").build()
                    ).build()
                ).build()
        );
        Cdr cdrRequest = aCdr("NONE").withField("field1", "value1").build();
        BlockingQueue<Cdr> cdrMessages1 = new LinkedBlockingQueue<>();
        BlockingQueue<Cdr> cdrMessages2 = new LinkedBlockingQueue<>();

        transport.subscribeToQueue("queue1", cdr -> cdrMessages1.add(cdr));
        transport.subscribeToQueue("queue2", cdr -> cdrMessages2.add(cdr));
        transport.sendToExchange(cdrRequest, exchangeName);

        assertEquals("value1", cdrMessages1.poll(2, SECONDS).getAsString("field1"));
        assertEquals("value1", cdrMessages2.poll(2, SECONDS).getAsString("field1"));
        assertTrue(cdrMessages1.isEmpty());
        assertTrue(cdrMessages2.isEmpty());
    }

    @Test
    public void testDirectExchange() throws Exception {
        final String exchangeName = "exchangeDirect";
        ExchangeTransport producerTransport = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT).build()
                ).build()
        );
        ExchangeTransport consumerTransport = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT)
                        .addQueueConfig(
                            aQueueConfig("queue").build()
                        ).build()
                ).build()
        );
        CompletableFuture<Cdr> eventualMessage = new CompletableFuture<>();

        consumerTransport.subscribeToQueue("queue", cdr -> eventualMessage.complete(cdr));
        producerTransport.sendToExchange(aCdr("NONE").withField("field1", "value1").build(), exchangeName);

        Cdr message = eventualMessage.get(200, MILLISECONDS);
        assertNotNull(message);
        assertEquals("value1", message.getAsString("field1"));
    }

    @Test
    public void testDirectExchangeWithRouting() throws Exception {
        final String exchangeName = "exchangeDirectRouting";
        ExchangeTransport producerTransport = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, DIRECT).build()
            ).build()
        );
        ExchangeTransport consumerTransport1 = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT)
                        .addQueueConfig(
                            aQueueConfig("queue1")
                            .withBindingKey("odd")
                            .build()
                        ).build()
                ).build()
        );
        ExchangeTransport consumerTransport2 = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, DIRECT)
                .addQueueConfig(
                    aQueueConfig("queue2")
                    .withBindingKey("even")
                    .build()
                ).build()
            ).build()
        );
        BlockingQueue<Cdr> cdrMessages1 = new LinkedBlockingQueue<>();
        BlockingQueue<Cdr> cdrMessages2 = new LinkedBlockingQueue<>();
        Cdr cdrRequest = aCdr("NONE").withField("field1", "value1").build();

        consumerTransport1.subscribeToQueue("queue1", cdr -> cdrMessages1.add(cdr));
        consumerTransport2.subscribeToQueue("queue2", cdr -> cdrMessages2.add(cdr));
        producerTransport.sendToExchange(cdrRequest, exchangeName, "even");

        assertEquals("value1", cdrMessages2.poll(2, SECONDS).getAsString("field1"));
        assertTrue(cdrMessages1.isEmpty());
        assertTrue(cdrMessages2.isEmpty());
    }

    @Test
    public void testDirectExchangeWithRoutingAndDuplicateBindingKey() throws Exception {
        final String exchangeName = "exchangeDirectBindingKey";
        ExchangeTransport producerTransport = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT).build()
                ).build()
        );
        ExchangeTransport consumerTransport1 = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT)
                        .addQueueConfig(
                            aQueueConfig("queue1")
                                .withBindingKey("routingKey")
                                .build()
                        ).build()
                ).build()
        );
        ExchangeTransport consumerTransport2 = createAndInitializeTransport(
            aAmqpConfig()
                .addExchangeConfig(
                    aExchangeConfig(exchangeName, DIRECT)
                        .addQueueConfig(
                            aQueueConfig("queue2")
                                .withBindingKey("routingKey")
                                .build()
                        ).build()
                ).build()
        );
        CompletableFuture<Cdr> eventualMessage1 = new CompletableFuture<>();
        CompletableFuture<Cdr> eventualMessage2 = new CompletableFuture<>();
        Cdr cdrRequest = aCdr("NONE").withField("field1", "value1").build();

        consumerTransport1.subscribeToQueue("queue1", cdr -> eventualMessage1.complete(cdr));
        consumerTransport2.subscribeToQueue("queue2", cdr -> eventualMessage2.complete(cdr));
        producerTransport.sendToExchange(cdrRequest, exchangeName, "routingKey");

        Cdr message1 = eventualMessage2.get(200, MILLISECONDS);
        assertNotNull(message1);
        assertEquals("value1", message1.getAsString("field1"));
        Cdr message2 = eventualMessage2.get(200, MILLISECONDS);
        assertNotNull(message2);
        assertEquals("value1", message2.getAsString("field1"));

    }

    /**
     * This test case is described at https://www.rabbitmq.com/tutorials/tutorial-five-java.html
     */
    @Test
    public void testTopicExchange() throws Exception {
        final String exchangeName = "exchangeTopic";
        ExchangeTransport producerTransport = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, TOPIC).build()
            ).build()
        );
        ExchangeTransport consumerTransport1 = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, TOPIC)
                .addQueueConfig(
                    aQueueConfig("queue1")
                    .withBindingKey("*.orange.*")
                    .build()
                )
                .build()
            ).build()
        );
        ExchangeTransport consumerTransport2 = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, TOPIC)
                .addQueueConfig(
                    aQueueConfig("queue2")
                    .withBindingKey("*.*.rabbit")
                    .build()
                ).addQueueConfig(
                    aQueueConfig("queue2")
                    .withBindingKey("lazy.#")
                    .build()
                )
                .build()
            ).build()
        );

        String[] messageRoutingKeys = new String[]{"quick.orange.rabbit", "lazy.orange.elephant", "quick.orange.fox",
            "lazy.brown.fox", "lazy.pink.rabbit", "quick.brown.fox"};
        BlockingQueue<Cdr> cdrMessages1 = new LinkedBlockingQueue<>();
        BlockingQueue<Cdr> cdrMessages2 = new LinkedBlockingQueue<>();

        consumerTransport1.subscribeToQueue("queue1", cdr -> cdrMessages1.add(cdr));
        consumerTransport2.subscribeToQueue("queue2", cdr -> cdrMessages2.add(cdr));
        for (String messageRoutingKey : messageRoutingKeys) {
            Cdr cdr = aCdr("NONE").withField("key", messageRoutingKey).build();
            producerTransport.sendToExchange(cdr, exchangeName, messageRoutingKey);
        }
        Thread.sleep(200);

        List<String> messageKeys1 = cdrMessages1.stream().map(cdr -> cdr.getAsString("key")).collect(toList());
        assertEquals(3, messageKeys1.size());
        assertTrue(messageKeys1.containsAll(Arrays.asList(new String[]{messageRoutingKeys[0], messageRoutingKeys[1], messageRoutingKeys[2]})));
        List<String> messageKeys2 = cdrMessages2.stream().map(cdr -> cdr.getAsString("key")).collect(toList());
        assertEquals(4, messageKeys2.size());
        assertTrue(messageKeys2.containsAll(Arrays.asList(new String[]{messageRoutingKeys[0], messageRoutingKeys[1], messageRoutingKeys[3], messageRoutingKeys[4]})));
    }

    @Test
    public void testFanout_withProtobuf() throws Exception {
        final String exchangeName = "exchangeFanoutProtobuf";
        ProtobufCodec codec = new ProtobufCodec();
        codec.setDictionary(new ProtobufDictionary("config/dictionary/addressbook.desc"));
        ExchangeTransport transport = createAndInitializeTransport(
            aAmqpConfig()
            .addExchangeConfig(
                aExchangeConfig(exchangeName, FANOUT)
                .addQueueConfig(
                    aQueueConfig("queue1").build()
                ).addQueueConfig(
                    aQueueConfig("queue2").build()
                ).build()
            ).build(),
            codec
        );
        Cdr cdrRequest = aCdr("Person")
            .withField("name", "PersonName")
            .withField("id", 34)
            .build();
        BlockingQueue<Cdr> cdrMessages1 = new LinkedBlockingQueue<>();
        BlockingQueue<Cdr> cdrMessages2 = new LinkedBlockingQueue<>();

        transport.subscribeToQueue("queue1", cdr -> cdrMessages1.add(cdr));
        transport.subscribeToQueue("queue2", cdr -> cdrMessages2.add(cdr));
        transport.sendToExchange(cdrRequest, exchangeName);

        assertEquals("PersonName", cdrMessages1.poll(2, SECONDS).getAsString("name"));
        assertEquals("PersonName", cdrMessages2.poll(2, SECONDS).getAsString("name"));
        assertTrue(cdrMessages1.isEmpty());
        assertTrue(cdrMessages2.isEmpty());
    }

    private ExchangeTransport createAndInitializeTransport(AmqpConfig config) throws Exception {
        return createAndInitializeTransport(config, new JsonCodec());
    }

    private ExchangeTransport createAndInitializeTransport(AmqpConfig config, Codec codec) throws Exception {
        AmqpConfigExtractor extractor = mock(AmqpConfigExtractor.class);
        when(extractor.retrieveConfiguration(anyString())).thenReturn(config);
        final ExchangeTransport transport = new RabbitMqTransport(extractor);
        transport.setCodec(codec);
        transport.init("");
        transports.add(transport);
        return transport;
    }

}