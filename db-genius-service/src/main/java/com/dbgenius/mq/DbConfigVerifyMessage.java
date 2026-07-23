package com.dbgenius.mq;

import java.io.Serializable;

/**
 * 触发“数据库连接校验 + 文档生成”的消息队列消息。
 *
 * <p>这是一个 Java 16 引入的 {@code record}（记录类），专门用来定义只读的数据载体，
 * 这里用作一条 MQ 消息。括号里的 {@code (Long configId)} 就是它的字段，
 * 编译器会自动帮你生成以下内容，无需手写：
 * <ul>
 *   <li>带 {@code configId} 参数的构造方法</li>
 *   <li>访问器方法 {@code configId()}（注意不是 {@code getConfigId()}）</li>
 *   <li>{@code equals()} / {@code hashCode()} / {@code toString()}</li>
 * </ul>
 *
 * <p>字段自动是 {@code final}、不可变，天生线程安全，很适合当消息对象。
 * 实现 {@link Serializable} 让它能被序列化，从而在消息队列中传输。
 *
 * <p>使用示例：
 * <pre>{@code
 * // 创建消息并发送
 * DbConfigVerifyMessage msg = new DbConfigVerifyMessage(123L);
 *
 * // 读取字段值（访问器方法名与字段同名）
 * Long id = msg.configId();
 *
 * // toString() 自动生成，形如：DbConfigVerifyMessage[configId=123]
 * System.out.println(msg);
 * }</pre>
 *
 * <p>如果不用 {@code record}，改用传统 {@code class} 写法，等价于下面这一大段：
 * <pre>{@code
 * public final class DbConfigVerifyMessage implements Serializable {
 *     private final Long configId;
 *
 *     public DbConfigVerifyMessage(Long configId) {
 *         this.configId = configId;
 *     }
 *
 *     public Long configId() {
 *         return configId;
 *     }
 *
 *     @Override
 *     public boolean equals(Object o) {
 *         if (this == o) return true;
 *         if (o == null || getClass() != o.getClass()) return false;
 *         DbConfigVerifyMessage that = (DbConfigVerifyMessage) o;
 *         return Objects.equals(configId, that.configId);
 *     }
 *
 *     @Override
 *     public int hashCode() {
 *         return Objects.hash(configId);
 *     }
 *
 *     @Override
 *     public String toString() {
 *         return "DbConfigVerifyMessage[configId=" + configId + "]";
 *     }
 * }
 * }</pre>
 *
 * @param configId 需要校验并生成文档的数据库配置 ID
 */
public record DbConfigVerifyMessage(Long configId) implements Serializable {
}
