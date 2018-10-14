//package net.devslash.runner
//
//import org.junit.jupiter.api.Assertions.assertEquals
//import org.junit.jupiter.api.Test
//
//internal class UrlProvidersTest {
//  @Test
//  fun testBasicProvider() {
//    val url = "http://example.com"
//    val provider = getUrlProvider(getCall(url = url), requestDataFromList(listOf()))
//    assertEquals(url, provider.get())
//  }
//
//  @Test
//  fun testReplacement() {
//    val provider = getUrlProvider(getCall(url = "http://!1"), requestDataFromList(listOf("google.com")))
//    assertEquals("http://google.com", provider.get())
//  }
//}
