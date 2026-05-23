package io.github.hyshmily.hotkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hotkey")
public class HotKeyProperties {

  private int topK = 100;
  private int width = 100_000;
  private int depth = 5;
  private double decay = 0.92;
  private int minCount = 10;
  private int localCacheMaxSize = 1000;
  private int localCacheTtlMinutes = 5;
  private int decayPeriod = 20;

  public int getTopK() { return topK; }
  public void setTopK(int topK) { this.topK = topK; }

  public int getWidth() { return width; }
  public void setWidth(int width) { this.width = width; }

  public int getDepth() { return depth; }
  public void setDepth(int depth) { this.depth = depth; }

  public double getDecay() { return decay; }
  public void setDecay(double decay) { this.decay = decay; }

  public int getMinCount() { return minCount; }
  public void setMinCount(int minCount) { this.minCount = minCount; }

  public int getLocalCacheMaxSize() { return localCacheMaxSize; }
  public void setLocalCacheMaxSize(int localCacheMaxSize) { this.localCacheMaxSize = localCacheMaxSize; }

  public int getLocalCacheTtlMinutes() { return localCacheTtlMinutes; }
  public void setLocalCacheTtlMinutes(int localCacheTtlMinutes) { this.localCacheTtlMinutes = localCacheTtlMinutes; }

  public int getDecayPeriod() { return decayPeriod; }
  public void setDecayPeriod(int decayPeriod) { this.decayPeriod = decayPeriod; }
}
