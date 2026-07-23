package com.dbgenius.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑配置：声明"验证数据库配置并生成文档"这条异步链路所需的全部 AMQP 组件。
 *
 * <p>RabbitMQ 的核心模型：生产者不直接发消息到队列，而是发到「交换机（Exchange）」，
 * 交换机根据「路由键（Routing Key）」和「绑定（Binding）」规则，把消息投递到一个或多个队列，
 * 消费者再从队列里取消息。因此 Exchange、Queue、Binding 三样东西必须配套声明，缺一不可。</p>
 *
 * <p>本类的整体消息链路：</p>
 * <pre>
 * DbConfigVerifyProducer.send(configId)
 *        │  convertAndSend(EXCHANGE, "dbconfig.verify", message)
 *        ▼
 * dbgenius.dbconfig.exchange (DirectExchange, durable)
 *        │  按路由键精确匹配 Binding："dbconfig.verify"
 *        ▼
 * dbgenius.dbconfig.verify (Queue, durable, 挂 DLX)
 *        │  @RabbitListener 消费（DbConfigVerifyConsumer）
 *        ▼
 * autoVerifyAndGenerateDoc() 验证连接 + 生成文档
 *        │  消费抛异常 → 本地重试 3 次（2s 起步 ×2 退避，见 application.yml）
 *        ▼  重试耗尽 / 消息被 reject 且不重新入队
 * dbgenius.dbconfig.dlx (死信交换机, DirectExchange, durable)
 *        │  路由键 "dbconfig.verify.dlq"
 *        ▼
 * dbgenius.dbconfig.verify.dlq (死信队列, durable) —— 人工兜底排查
 * </pre>
 *
 * <p>关键词速记：</p>
 * <ul>
 *   <li><b>DirectExchange</b>：直连交换机，路由键与 Binding 完全相等才投递（最常用、最精确的一种）。</li>
 *   <li><b>durable（持久化）</b>：交换机/队列的元数据落盘，RabbitMQ 重启后不丢失。
 *       注意：队列持久化 ≠ 消息必然不丢，消息本身也要以持久化方式发送
 *       （Spring AMQP 默认 MessageDeliveryMode.PERSISTENT，无需额外配置）。</li>
 *   <li><b>DLX / DLQ（死信交换机/死信队列）</b>：队列上声明的"后事安排"。
 *       消息被消费者拒绝（reject/nack 且 requeue=false）、过期（TTL）、或队列溢出时，
 *       不会直接丢弃，而是被 RabbitMQ 原样转发到 DLX，再落入 DLQ，便于事后排查和人工重发。</li>
 *   <li><b>Binding</b>：交换机与队列之间的"订阅关系"，携带路由键，决定消息流向。</li>
 * </ul>
 *
 * <p>这些 @Bean 声明后，Spring AMQP 的 RabbitAdmin 会在首次连接时自动在 broker 上创建它们
 * （已存在且参数一致则跳过），因此无需在管理界面手工建队列。</p>
 */
@Configuration
public class RabbitMqConfig {

    /**
     * 主交换机：接收生产者发来的验证任务消息。
     *
     * <p>参数：name=交换机名, durable=true（重启不丢）, autoDelete=false（无绑定时也不自动删除）。</p>
     */
    @Bean
    public DirectExchange dbConfigExchange() {
        return new DirectExchange(DbConfigMqConstants.EXCHANGE, true, false);
    }

    /**
     * 主队列：存放待消费的验证任务。
     *
     * <p>durable 保证队列元数据（及其中的持久化消息）在 broker 重启后仍在——
     * 这正是替换掉原 @Async 方案的核心收益：应用或 MQ 重启，未处理的任务不会丢。</p>
     *
     * <p>deadLetterExchange / deadLetterRoutingKey：给队列指定"死信去向"。
     * 当消息消费失败且本地重试耗尽后（或消费者 nack 且 requeue=false），
     * RabbitMQ 会用这个路由键把消息转发到 DLX，最终进入死信队列。</p>
     */
    @Bean
    public Queue dbConfigVerifyQueue() {
        return QueueBuilder.durable(DbConfigMqConstants.QUEUE_VERIFY)
                .deadLetterExchange(DbConfigMqConstants.DLX)
                .deadLetterRoutingKey(DbConfigMqConstants.ROUTING_KEY_VERIFY_DLQ)
                .build();
    }

    /**
     * 绑定：主交换机 → 主队列。
     *
     * <p>含义："凡是发到 dbConfigExchange 且路由键等于 'dbconfig.verify' 的消息，
     * 都投递到 dbConfigVerifyQueue"。DirectExchange 要求路由键精确相等。</p>
     */
    @Bean
    public Binding dbConfigVerifyBinding(Queue dbConfigVerifyQueue, DirectExchange dbConfigExchange) {
        return BindingBuilder.bind(dbConfigVerifyQueue)
                .to(dbConfigExchange)
                .with(DbConfigMqConstants.ROUTING_KEY_VERIFY);
    }

    /**
     * 死信交换机（DLX）：就是一个普通的 DirectExchange，
     * "死信"这个身份完全由主队列上的 deadLetterExchange 参数赋予，本身无任何特殊配置。
     */
    @Bean
    public DirectExchange dbConfigDlx() {
        return new DirectExchange(DbConfigMqConstants.DLX, true, false);
    }

    /**
     * 死信队列（DLQ）：存放所有重试耗尽后仍失败的消息。
     *
     * <p>没有消费者监听它——它是"保险箱"，消息会一直留着，
     * 运维可在管理界面（http://localhost:15672）查看失败原因或手动重新投递。</p>
     */
    @Bean
    public Queue dbConfigVerifyDlq() {
        return QueueBuilder.durable(DbConfigMqConstants.QUEUE_VERIFY_DLQ).build();
    }

    /**
     * 绑定：死信交换机 → 死信队列。
     *
     * <p>路由键必须与主队列上声明的 deadLetterRoutingKey 一致，
     * 否则死信消息转发过来后无处可去，会被直接丢弃。</p>
     */
    @Bean
    public Binding dbConfigVerifyDlqBinding(Queue dbConfigVerifyDlq, DirectExchange dbConfigDlx) {
        return BindingBuilder.bind(dbConfigVerifyDlq)
                .to(dbConfigDlx)
                .with(DbConfigMqConstants.ROUTING_KEY_VERIFY_DLQ);
    }

    /**
     * 消息序列化方式：默认是 JDK 序列化（跨语言不友好、依赖类路径），
     * 这里换成 JSON——消息体在管理界面上可读，且生产/消费两端只依赖字段名。
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 生产者侧的发送模板：覆盖 Spring Boot 自动配置的 RabbitTemplate，
     * 目的只有一个——挂上 JSON 转换器，让 convertAndSend 发出的是 JSON 消息。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jackson2JsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jackson2JsonMessageConverter);
        return template;
    }

    /**
     * 消费者侧的监听容器工厂：覆盖 Spring Boot 自动配置的工厂，
     * 让 @RabbitListener 收到的 JSON 消息能反序列化回 DbConfigVerifyMessage 对象。
     * 生产端和消费端必须使用同一种序列化方式，否则消息会解析失败进入重试/DLQ。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jackson2JsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jackson2JsonMessageConverter);
        return factory;
    }
}
