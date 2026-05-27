package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun downloadBCrypt() {
    try {
      val cls = Class.forName("com.sun.crypto.provider.BlowfishCrypt")
      val piField = cls.getDeclaredField("pi")
      piField.isAccessible = true
      val piValue = piField.get(null) as IntArray
      
      assertEquals(1042, piValue.size)
      
      val sOrigList = ArrayList<Int>()
      for (i in 18 until 1042) {
        sOrigList.add(piValue[i])
      }
      
      assertEquals(1024, sOrigList.size)
      
      val sb = StringBuilder()
      sb.append("{\n        ")
      for (i in sOrigList.indices) {
        sb.append(String.format("0x%08x", sOrigList[i]))
        if (i < sOrigList.size - 1) {
          sb.append(", ")
        }
        if (i % 8 == 7 && i < sOrigList.size - 1) {
          sb.append("\n        ")
        }
      }
      sb.append("\n    }")
      
      val targetFile = java.io.File("../app/src/main/java/com/example/security/BCrypt.java")
      val actualFile = if (targetFile.exists()) targetFile else java.io.File("src/main/java/com/example/security/BCrypt.java")
      
      val originalCode = actualFile.readText(Charsets.UTF_8)
      val sOrigStart = originalCode.indexOf("private static final int[] S_orig = {")
      if (sOrigStart == -1) {
        fail("Could not find S_orig declaration in BCrypt.java")
      }
      val sBoxStart = originalCode.indexOf("{", sOrigStart)
      val sBoxEnd = originalCode.indexOf("};", sBoxStart)
      if (sBoxEnd == -1) {
        fail("Could not find S_orig end in BCrypt.java")
      }
      
      val newCode = originalCode.substring(0, sBoxStart) + sb.toString() + originalCode.substring(sBoxEnd)
      actualFile.writeText(newCode, Charsets.UTF_8)
      
      java.io.File("src/test/java/com/example/ErrorLog.txt").writeText("PATCH SUCCESSFUL! Wrote 1024 S_orig elements using JDK pi field.", Charsets.UTF_8)
    } catch (e: Exception) {
      val sw = java.io.StringWriter()
      e.printStackTrace(java.io.PrintWriter(sw))
      java.io.File("src/test/java/com/example/ErrorLog.txt").writeText("Patch failed: ${e.message}\n${sw.toString()}", Charsets.UTF_8)
      fail("Patch failed: ${e.message}")
    }
  }
}
