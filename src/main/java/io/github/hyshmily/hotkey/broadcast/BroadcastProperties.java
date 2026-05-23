package io.github.hyshmily.hotkey.broadcast;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hotkey.broadcast")
public class BroadcastProperties {

  private boolean enabled = false;
  private String exchangeName = "hotkey.broadcast.exchange";
  private String queuePrefix = "hotkey.broadcast";
  private String instanceId = "${server.port:instance}";
  private int dedupWindowSeconds = 10;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }

  public String getExchangeName() { return exchangeName; }
  public void setExchangeName(String exchangeName) { this.exchangeName = exchangeName; }

  public String getQueuePrefix() { return queuePrefix; }
  public void setQueuePrefix(String queuePrefix) { this.queuePrefix = queuePrefix; }

  public String getInstanceId() { return instanceId; }
  public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

  public int getDedupWindowSeconds() { return dedupWindowSeconds; }
  public void setDedupWindowSeconds(int dedupWindowSeconds) { this.dedupWindowSeconds = dedupWindowSeconds; }

  public String getQueueName() {
    return queuePrefix + ":" + instanceId;
  }
}
