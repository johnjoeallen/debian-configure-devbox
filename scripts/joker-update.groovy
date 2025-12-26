#!/usr/bin/env groovy
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JokerUpdater {
  static void main(String[] args) {
    def configPath = args ? args[0] : System.getenv('JOKER_CONFIG') ?: '/etc/joker.yaml'
    def configFile = new File(configPath)
    if (!configFile.exists()) {
      System.err.println("⚠️  Configuration not found at ${configPath}")
      System.exit(1)
    }
    def yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
    def raw = yaml.load(configFile.text)
    if (!(raw instanceof Map)) {
      System.err.println("⚠️  Unexpected config structure in ${configPath}")
      System.exit(1)
    }
    Map cfg = raw as Map

    def ipSource = cfg.ipSource?.toString()?.trim() ?: 'https://checkip.amazonaws.com'
    def cacheFile = new File(cfg.cacheFile?.toString()?.trim() ?: '/etc/cachedexternalip')
    def jokerEndpoint = cfg.jokerEndpoint?.toString()?.trim() ?: 'https://svc.joker.com/nic/update'
    def timeoutMs = (cfg.timeoutSeconds instanceof Number ? cfg.timeoutSeconds as int : 10) * 1000
    def updates = (cfg.updates instanceof Collection) ? cfg.updates : []

    cacheFile.parentFile?.mkdirs()
    if (!cacheFile.exists()) {
      cacheFile.write('')
    }
    def cachedIp = cacheFile.text?.trim()
    def externalIp = fetchExternalIp(ipSource, timeoutMs)
    if (!externalIp) {
      System.err.println("⚠️  Unable to determine external IP from ${ipSource}")
      System.exit(1)
    }
    if (externalIp == cachedIp) {
      println("✔️  External IP unchanged (${externalIp})")
      return
    }
    cacheFile.write("${externalIp}\n")

    def eligible = updates.findAll { entry ->
      (entry instanceof Map) && boolFlag(entry.disabled, false) == false
    }
    if (eligible.isEmpty()) {
      println("ℹ️  No update targets configured in ${configPath}")
      return
    }

    eligible.each { entry ->
      def user = entry.user?.toString()?.trim()
      def pass = entry.password?.toString()?.trim()
      def domain = entry.hostname?.toString()?.trim() ?: entry.domain?.toString()?.trim()
      if (!user || !pass || !domain) {
        System.err.println("⚠️  Skipping incomplete entry: ${entry}")
        return
      }
      def result = callUpdate(jokerEndpoint, user, pass, domain, externalIp, timeoutMs)
      println("→ ${domain}: ${result}")
    }
  }

  private static String fetchExternalIp(String source, int timeout) {
    try {
      def url = new URL(source)
      def conn = (HttpURLConnection) url.openConnection()
      conn.setConnectTimeout(timeout)
      conn.setReadTimeout(timeout)
      conn.setRequestProperty('User-Agent', 'joker-updater/1.0')
      conn.inputStream.withReader(StandardCharsets.UTF_8) { reader ->
        return reader.readLine()?.trim()
      }
    } catch (Exception e) {
      System.err.println("⚠️  Failed to fetch IP: ${e.message}")
      return null
    }
  }

  private static String callUpdate(String endpoint, String user, String pass, String domain, String ip, int timeout) {
    try {
      def query = [
        username: user,
        password: pass,
        hostname: domain,
        myip: ip
      ].collect { k, v -> "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}" }.join('&')
      def url = new URL("${endpoint}?${query}")
      def conn = (HttpURLConnection) url.openConnection()
      conn.setConnectTimeout(timeout)
      conn.setReadTimeout(timeout)
      conn.setRequestProperty('User-Agent', 'joker-updater/1.0')
      def response = conn.inputStream.withReader(StandardCharsets.UTF_8) { it.readLine()?.trim() }
      return response ?: "no response"
    } catch (Exception e) {
      return "error (${e.message})"
    }
  }

  private static boolean boolFlag(Object value, boolean defaultValue) {
    if (value == null) {
      return defaultValue
    }
    if (value instanceof Boolean) {
      return value
    }
    def text = value.toString().trim().toLowerCase()
    if (!text) {
      return defaultValue
    }
    return ['1', 'true', 'yes', 'y'].contains(text)
  }
}

JokerUpdater.main(args)
