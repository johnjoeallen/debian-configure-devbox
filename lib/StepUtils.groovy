package lib

class StepUtils {
  static Map sh(String cmd) {
    println("🚀 ${cmd}")
    def process = ["bash", "-lc", cmd].execute()
    def out = new StringBuffer()
    def err = new StringBuffer()
    process.consumeProcessOutput(out, err)
    process.waitFor()
    [code: process.exitValue(), out: out.toString().trim(), err: err.toString().trim()]
  }

  static void writeText(String path, String content) {
    new File(path).withWriter { it << content }
  }

  static String backup(String path) {
    def src = new File(path)
    if (!src.exists()) {
      return null
    }
    def bak = path + ".bak." + System.currentTimeMillis()
    src.withInputStream { input ->
      new File(bak).withOutputStream { output ->
        output << input
      }
    }
    return bak
  }
}
