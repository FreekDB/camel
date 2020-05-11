/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.management;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.seda.SedaComponent;
import org.apache.camel.component.seda.SedaConsumer;
import org.apache.camel.component.seda.SedaEndpoint;
import org.apache.camel.impl.engine.DefaultSupervisingRouteController;
import org.apache.camel.spi.SupervisingRouteController;
import org.junit.Test;

import static org.awaitility.Awaitility.await;

public class ManagedSupervisingRouteControllerTest extends ManagementTestSupport {

    @Override
    protected CamelContext createCamelContext() throws Exception {
        CamelContext context = super.createCamelContext();
        SupervisingRouteController src = new DefaultSupervisingRouteController();
        src.setThreadPoolSize(2);
        src.setBackOffDelay(250);
        src.setBackOffMaxAttempts(100);
        context.setRouteController(src);
        return context;
    }

    @Test
    public void testSupervisingRouteController() throws Exception {
        // JMX tests dont work well on AIX CI servers (hangs them)
        if (isPlatform("aix")) {
            return;
        }

        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        // get the object name for the delayer
        ObjectName on = ObjectName.getInstance("org.apache.camel:context=camel-1,type=routecontrollers,name=DefaultSupervisingRouteController");
        assertTrue(mbeanServer.isRegistered(on));

        Boolean enabled = (Boolean) mbeanServer.getAttribute(on, "Enabled");
        assertTrue(enabled);

        Integer threadPoolSize = (Integer) mbeanServer.getAttribute(on, "ThreadPoolSize");
        assertEquals(2, threadPoolSize.intValue());

        Long backOffDelay = (Long) mbeanServer.getAttribute(on, "BackOffDelay");
        assertEquals(250, backOffDelay.intValue());

        Integer routes = (Integer) mbeanServer.getAttribute(on, "NumberOfControlledRoutes");
        assertEquals(3, routes.intValue());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer restarting = (Integer) mbeanServer.getAttribute(on, "NumberOfRestartingRoutes");
            assertEquals(2, restarting.intValue());
        });

        TabularData data = (TabularData) mbeanServer.invoke(on, "routeStatus", new Object[]{true, true}, new String[]{"boolean", "boolean"});
        assertNotNull(data);
        assertEquals(2, data.size());
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                getContext().addComponent("jms", new MyJmsComponent());

                from("timer:foo").to("mock:foo").routeId("foo");

                from("jms:cheese").to("mock:cheese").routeId("cheese");

                from("jms:cake").to("mock:cake").routeId("cake");

                from("seda:bar").routeId("bar").noAutoStartup().to("mock:bar");
            }
        };
    }

    private class MyJmsComponent extends SedaComponent {

        @Override
        protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
            return new MyJmsEndpoint();
        }
    }

    private class MyJmsEndpoint extends SedaEndpoint {

        public MyJmsEndpoint() {
            super();
        }

        @Override
        public Consumer createConsumer(Processor processor) throws Exception {
            return new MyJmsConsumer(this, processor);
        }

        @Override
        protected String createEndpointUri() {
            return "jms:cheese";
        }
    }

    private class MyJmsConsumer extends SedaConsumer {

        public MyJmsConsumer(SedaEndpoint endpoint, Processor processor) {
            super(endpoint, processor);
        }

        @Override
        protected void doStart() throws Exception {
            throw new IllegalArgumentException("Cannot start");
        }
    }
}
