package lib

class ConfigLoader {
  private static Map cachedConfig = null
  private static Map cachedMeta = null
  private static Set<String> missingWarned = new LinkedHashSet<>()

  static Map loadAll() {
    if (cachedConfig != null) {
      return cachedConfig
    }
    def profileDir = resolveProfileDir()
    def profileNames = resolveProfileNames()
    List<File> profileFiles = []
    Map mergedProfiles = [:]
    profileNames.each { String name ->
      File file = profileFile(profileDir, name)
      profileFiles << file
      mergedProfiles = deepMerge(mergedProfiles, parseYamlFile(file))
    }
    def baseFile = resolveBaseFile()
    def hostFile = resolveHostFile()
    def baseCfg = parseYamlFile(baseFile)
    def hostCfg = parseYamlFile(hostFile)
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

  private static List<String> resolveProfileNames() {
    def raw = System.getenv('CONFIG_PROFILES') ?: ''
    raw.split(',')
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
    def lines = file.readLines()
    def root = [:]
    def stack = [[indent:-1, container:root, type:'map']]

    def nextNonEmpty = { int start ->
      for (int i = start; i < lines.size(); i++) {
        def candidate = stripComment(lines[i].replace('\t', '    '))
        if (candidate.trim()) {
          return candidate.trim()
        }
      }
      return null
    }

    for (int idx = 0; idx < lines.size(); idx++) {
      def rawLine = lines[idx]
      def line = rawLine.replace('\t', '    ')
      def stripped = stripComment(line)
      if (!stripped.trim()) {
        continue
      }
      int indent = leadingSpaces(stripped)
      String trimmed = stripped.trim()

      while (stack.size() > 1 && indent <= stack[-1].indent) {
        stack.remove(stack.size()-1)
      }
      def frame = stack[-1]

      if (trimmed.startsWith('- ')) {
        if (frame.type != 'list') {
          throw new IllegalArgumentException("List item without list parent: ${rawLine}")
        }
        def valueText = trimmed.substring(2).trim()
        if (!valueText) {
          def newMap = [:]
          frame.container << newMap
          stack << [indent:indent, container:newMap, type:'map']
        } else {
          frame.container << parseScalar(valueText)
        }
        continue
      }

      def parts = trimmed.split(':', 2)
      if (parts.length < 2) {
        throw new IllegalArgumentException("Unsupported YAML line: ${rawLine}")
      }
      def key = parts[0].trim()
      def valuePart = parts[1].trim()

      if (valuePart.isEmpty()) {
        def lookahead = nextNonEmpty(idx + 1)
        boolean expectList = (lookahead != null && lookahead.startsWith('- '))
        if (expectList) {
          def list = []
          frame.container[key] = list
          stack << [indent:indent, container:list, type:'list']
        } else {
          def newMap = [:]
          frame.container[key] = newMap
          stack << [indent:indent, container:newMap, type:'map']
        }
      } else {
        def value = parseScalar(valuePart)
        frame.container[key] = value
      }
    }
    return root
  }

  private static String stripComment(String line) {
    boolean inSingle = false
    boolean inDouble = false
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i)
      if (ch == '"' && !inSingle) {
        inDouble = !inDouble
      } else if (ch == '\'' && !inDouble) {
        inSingle = !inSingle
      }
      if (!inSingle && !inDouble && ch == '#') {
        break
      }
      sb.append(ch)
    }
    return sb.toString()
  }

  private static int leadingSpaces(String line) {
    int count = 0
    while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
      count++
    }
    return count
  }

  private static Object parseScalar(String value) {
    def text = value.trim()
    if (text.equalsIgnoreCase('null') || text == '~') {
      return null
    }
    if (text.equalsIgnoreCase('true')) {
      return true
    }
    if (text.equalsIgnoreCase('false')) {
      return false
    }
    if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith('\'') && text.endsWith('\''))) {
      return text.substring(1, text.length()-1)
    }
    if (text.isNumber()) {
      if (text.contains('.') || text.contains('e') || text.contains('E')) {
        return Double.parseDouble(text)
      }
      try {
        return Long.parseLong(text)
      } catch (NumberFormatException ignore) {}
    }
    return text
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
