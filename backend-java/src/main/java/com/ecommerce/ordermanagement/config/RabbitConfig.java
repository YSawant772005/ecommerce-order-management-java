package com.ecommerce.ordermanagement.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * RabbitMQ transport. RabbitMQ is transport only; it is never a strategy.
 *
 * <p>The former Celery app used the same {@code RABBITMQ_URL} AMQP DSN, so this
 * parses that DSN rather than introducing new configuration. One durable queue
 * carries {@code (order_id, outbox_id)} sync messages producer → worker.</p>
 */
@Configuration
public class RabbitConfig {

    /** The exact transport concept: {@code es.sync} messages producer → worker. */
    public static final String SYNC_QUEUE = "es.sync";

    @Bean
    public ConnectionFactory connectionFactory(AppProperties props) {
        URI url = URI.create(props.getRabbitmqUrl());
        String host = url.getHost();
        int port = url.getPort() == -1 ? 5672 : url.getPort();
        String userInfo = url.getUserInfo() == null ? "" : url.getUserInfo();
        String user = userInfo.contains(":") ? userInfo.substring(0, userInfo.indexOf(':')) : userInfo;
        String password = userInfo.contains(":") ? userInfo.substring(userInfo.indexOf(':') + 1) : "";
        String path = url.getPath() == null ? "" : url.getPath().replaceFirst("^/", "");
        String vhost = path.isEmpty() ? "/" : path;

        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setVirtualHost(vhost);
        return factory;
    }

    @Bean
    public Queue esSyncQueue() {
        return new Queue(SYNC_QUEUE, true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
