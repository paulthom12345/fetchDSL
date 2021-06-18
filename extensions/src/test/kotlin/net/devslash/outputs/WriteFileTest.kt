package net.devslash.outputs

import net.devslash.HttpResponse
import net.devslash.ListRequestData
import net.devslash.OutputFormat
import net.devslash.RequestData
import net.devslash.util.getBasicRequest
import net.devslash.util.getBasicResponse
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class WriteFileTest {

    @Rule
    @JvmField
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `Test Write File Happy Path`() {
        val testFile = folder.newFile("test")
        val write = WriteFile(testFile.absolutePath)
        write.accept(getBasicRequest(), getBasicResponse(), ListRequestData(listOf<String>()))

        assertThat(testFile.readLines(), equalTo(listOf("Body")))
    }

    @Test
    fun `Test custom format write file`() {
        val testFile = folder.newFile("test")
        val testOutput = "Test\noutput".toByteArray()
        val write = WriteFile(testFile.absolutePath, object : OutputFormat {
            override fun accept(resp: HttpResponse, data: RequestData): ByteArray {
                return testOutput
            }
        })

        write.accept(getBasicRequest(), getBasicResponse(), ListRequestData(listOf<String>()))

        assertThat(testFile.readLines(), equalTo(listOf("Test", "output")))
    }
}