package lib

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

class ConfigLoader {
  private static Map cachedConfig = null
  private static Map cachedMeta = null
  private static Set<String> missingWarned = new LinkedHashSet<>()

  static Map loadAll() {
    if (cachedConfig != null) {
      return cachedConfig
    }
    def baseFile = resolveBaseFile()
    def hostFile = resolveHostFile()
    def baseCfg = parseYamlFile(baseFile)
    def hostCfg = parseYamlFile(hostFile)
    def profileDir = resolveProfileDir()
    def profileNames = resolveProfileNames(baseCfg, hostCfg)
    List<File> profileFiles = []
    Map mergedProfiles = [:]
    profileNames.each { String name ->
      File file = profileFile(profileDir, name)
      profileFiles << file
      mergedProfiles = deepMerge(mergedProfiles, parseYamlFile(file))
    }
    Map combined = deepMerge(mergedProfiles, baseCfg)
    combined = deepMerge(combined, hostCfg)
    cachedMeta = [profiles: profileFiles, base: baseFile, host: hostFile]
    cachedConfig = combined
    return cachedConfig
  }

  static Map meta() {
    loadAll()
    return cachedMeta
  }

  static Map stepConfig(String key) {
    def cfg = stepConfigNullable(key)
    if (cfg == null) {
      missingStep(key)
      return [:]
    }
    return cfg
  }

  static boolean stepEnabled(String key) {
    def cfg = stepConfigNullable(key)
    if (cfg == null) {
      missingStep(key)
      return false
    }
    if (cfg.containsKey('enabled') && cfg.enabled == false) {
      return false
    }
    return true
  }

  private static Map stepConfigNullable(String key) {
    def all = loadAll()
    def steps = (all.steps instanceof Map) ? all.steps : [:]
    if (!steps.containsKey(key)) {
      return null
    }
    def value = steps[key]
    if (value instanceof Map) {
      return new LinkedHashMap(value)
    }
    return [:]
  }

  private static Map deepMerge(Map base, Map override) {
    def result = [:]
    (base ?: [:]).each { k, v ->
      result[k] = cloneValue(v)
    }
    (override ?: [:]).each { k, v ->
      if (result.containsKey(k)) {
        result[k] = mergeValue(result[k], v)
      } else {
        result[k] = cloneValue(v)
      }
    }
    return result
  }

  private static Object mergeValue(Object base, Object override) {
    if (base instanceof Map && override instanceof Map) {
      return deepMerge((Map) base, (Map) override)
    }
    if (base instanceof List && override instanceof List) {
      return new ArrayList(base) + override
    }
    return cloneValue(override)
  }

  private static Object cloneValue(Object value) {
    if (value instanceof Map) {
      return deepMerge((Map) value, [:])
    }
    if (value instanceof List) {
      return new ArrayList(value)
    }
    return value
  }

  private static Map parseYamlFile(File file) {
    if (file == null || !file.exists()) {
      return [:]
    }
    return parseYaml(file)
  }

  private static void missingStep(String key) {
    if (missingWarned.contains(key)) {
      return
    }
    missingWarned << key
    def sources = []
    def meta = meta()
    def profiles = meta?.profiles ?: []
    profiles?.each { file ->
      if (file instanceof File && file.exists()) {
        sources << file.path
      }
    }
    if (meta?.base && meta.base.exists()) {
      sources << meta.base.path
    }
    if (meta?.host && meta.host.exists()) {
      sources << meta.host.path
    }
    def hint = sources ? sources.join(', ') : 'no configuration files located'
    System.err.println("⚠️  Missing configuration for step '${key}'. Assuming disabled. Add a '${key}' entry under 'steps' (checked: ${hint}).")
  }

  private static List<String> resolveProfileNames(Map baseCfg, Map hostCfg) {
    def raw = System.getenv('CONFIG_PROFILES') ?: ''
    def envProfiles = parseProfileNames(raw)
    if (!envProfiles.isEmpty()) {
      return envProfiles
    }
    def combined = new LinkedHashSet<String>()
    combined.addAll(extractProfileNames(baseCfg))
    combined.addAll(extractProfileNames(hostCfg))
    return combined as List
  }

  private static List<String> extractProfileNames(Map cfg) {
    if (cfg == null || !cfg.containsKey('profiles')) {
      return []
    }
    return parseProfileNames(cfg.profiles)
  }

  private static List<String> parseProfileNames(Object raw) {
    if (raw == null) {
      return []
    }
    def names = []
    if (raw instanceof Collection) {
      raw.each { entry ->
        def text = entry?.toString()?.trim()
        if (text) {
          names << text
        }
      }
      return names
    }
    def text = raw.toString()
    text.split(',')
      .collect { it.trim() }
      .findAll { it }
  }

  private static File resolveProfileDir() {
    def path = System.getenv('CONFIG_PROFILE_DIR')
    if (path) {
      return new File(path)
    }
    def root = System.getenv('CONFIG_ROOT') ?: '.'
    return new File(root, 'profiles')
  }

  private static File profileFile(File dir, String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException('Profile name may not be empty')
    }
    File direct = new File(dir, name)
    if (direct.exists() && direct.isFile()) {
      return direct
    }
    File withExt = new File(dir, name.endsWith('.yaml') ? name : name + '.yaml')
    return withExt
  }

  private static Map parseYaml(File file) {
    def options = new LoaderOptions()
    def yaml = new Yaml(new SafeConstructor(options))
    def data = yaml.load(file.getText('UTF-8'))
    if (data == null) {
      return [:]
    }
    if (!(data instanceof Map)) {
      throw new IllegalArgumentException("Root of ${file.path} must be a mapping, got ${data.getClass().simpleName()}")
    }
    return (Map) data
  }

  private static File resolveBaseFile() {
    def path = System.getenv('CONFIG_FILE')
    if (path) {
      return new File(path)
    }
    def root = System.getenv('CONFIG_ROOT')
    if (root) {
      return new File(root, 'config.yaml')
    }
    return new File('config.yaml')
  }

  private static File resolveHostFile() {
    def explicit = System.getenv('HOST_CONFIG_FILE')
    if (explicit) {
      return new File(explicit)
    }
    def root = System.getenv('CONFIG_ROOT') ?: '.'
    def host = System.getenv('CONFIG_HOSTNAME') ?: System.getenv('HOST_ID_SHORT') ?: System.getenv('HOSTNAME')
    if (!host) {
      try {
        host = java.net.InetAddress.localHost.hostName
      } catch (Exception ignored) {
        host = 'unknown'
      }
    }
    host = host?.tokenize('.')?.first() ?: 'unknown'
    return new File(root, "${host}.yaml")
  }
}
