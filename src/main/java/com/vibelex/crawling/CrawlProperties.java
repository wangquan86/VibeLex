package com.vibelex.crawling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vibelex.crawling")
public class CrawlProperties {
  private boolean enabled;
  private Worker worker = new Worker();
  private PopCidian popcidian = new PopCidian();
  private RegengBaike regengbaike = new RegengBaike();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Worker getWorker() {
    return worker;
  }

  public void setWorker(Worker worker) {
    this.worker = worker;
  }

  public PopCidian getPopcidian() {
    return popcidian;
  }

  public void setPopcidian(PopCidian popcidian) {
    this.popcidian = popcidian;
  }

  public RegengBaike getRegengbaike() {
    return regengbaike;
  }

  public void setRegengbaike(RegengBaike regengbaike) {
    this.regengbaike = regengbaike;
  }

  public static class Worker {
    private long fixedDelayMillis = 500;
    private int leaseSeconds = 120;
    private String actorId = "system";

    public long getFixedDelayMillis() {
      return fixedDelayMillis;
    }

    public void setFixedDelayMillis(long fixedDelayMillis) {
      this.fixedDelayMillis = fixedDelayMillis;
    }

    public int getLeaseSeconds() {
      return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
      this.leaseSeconds = leaseSeconds;
    }

    public String getActorId() {
      return actorId;
    }

    public void setActorId(String actorId) {
      this.actorId = actorId;
    }
  }

  public static class PopCidian {
    private boolean enabled;
    private boolean scheduledEnabled;
    private String baseUrl = "https://www.popcidian.com";
    private String sitemapUrl = "https://www.popcidian.com/sitemap.xml";
    private String syncCron = "0 30 3 * * *";
    private int requestTimeoutSeconds = 30;
    private int maximumAttempts = 3;
    private int maximumDiscoveredItems = 5000;
    private String userAgent = "VibeLexCrawler/3.0";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isScheduledEnabled() {
      return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
      this.scheduledEnabled = scheduledEnabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getSitemapUrl() {
      return sitemapUrl;
    }

    public void setSitemapUrl(String sitemapUrl) {
      this.sitemapUrl = sitemapUrl;
    }

    public String getSyncCron() {
      return syncCron;
    }

    public void setSyncCron(String syncCron) {
      this.syncCron = syncCron;
    }

    public int getRequestTimeoutSeconds() {
      return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
      this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaximumAttempts() {
      return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
      this.maximumAttempts = maximumAttempts;
    }

    public int getMaximumDiscoveredItems() {
      return maximumDiscoveredItems;
    }

    public void setMaximumDiscoveredItems(int maximumDiscoveredItems) {
      this.maximumDiscoveredItems = maximumDiscoveredItems;
    }

    public String getUserAgent() {
      return userAgent;
    }

    public void setUserAgent(String userAgent) {
      this.userAgent = userAgent;
    }
  }

  public static class RegengBaike {
    private boolean enabled;
    private boolean scheduledEnabled;
    private String baseUrl = "https://regengbaike.com";
    private String sitemapUrl = "https://regengbaike.com/addons/cms/sitemap/archives/sitemap.xml";
    private String syncCron = "0 0 4 * * *";
    private int requestTimeoutSeconds = 30;
    private int maximumAttempts = 3;
    private int maximumDiscoveredItems = 5000;
    private String userAgent = "VibeLexCrawler/3.1";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isScheduledEnabled() {
      return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
      this.scheduledEnabled = scheduledEnabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getSitemapUrl() {
      return sitemapUrl;
    }

    public void setSitemapUrl(String sitemapUrl) {
      this.sitemapUrl = sitemapUrl;
    }

    public String getSyncCron() {
      return syncCron;
    }

    public void setSyncCron(String syncCron) {
      this.syncCron = syncCron;
    }

    public int getRequestTimeoutSeconds() {
      return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
      this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaximumAttempts() {
      return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
      this.maximumAttempts = maximumAttempts;
    }

    public int getMaximumDiscoveredItems() {
      return maximumDiscoveredItems;
    }

    public void setMaximumDiscoveredItems(int maximumDiscoveredItems) {
      this.maximumDiscoveredItems = maximumDiscoveredItems;
    }

    public String getUserAgent() {
      return userAgent;
    }

    public void setUserAgent(String userAgent) {
      this.userAgent = userAgent;
    }
  }
}
