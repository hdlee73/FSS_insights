package com.hdlee73.everylaw.core.parser

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * 국가법령정보 DRF 응답 XML을 다루기 위한 가벼운 트리.
 *
 * DRF 응답은 한글 태그명(<조문내용>, <행정규칙명> 등)을 쓰고, 대상(target)마다 래퍼 구조가 조금씩 다르다.
 * 고정 스키마 대신 "이름이 X인/X를 포함하는 하위 요소"로 찾도록 해서 구조 변화에 덜 민감하게 만든다.
 * javax SAX 파서는 JVM과 안드로이드 모두에 있어 :core 모듈을 순수 Kotlin으로 유지할 수 있다.
 */
class XmlNode(
    val name: String,
    val text: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
) {
    /** 이름이 정확히 [tag]인 직계 자식 */
    fun child(tag: String): XmlNode? = children.firstOrNull { it.name == tag }

    fun childText(vararg tags: String): String? =
        tags.firstNotNullOfOrNull { tag -> child(tag)?.text?.takeIf { it.isNotBlank() } }

    /** 이름이 [tag]인 모든 하위 요소(깊이 우선, 자신 포함) */
    fun findAll(tag: String): List<XmlNode> = findAll { it.name == tag }

    fun findAll(predicate: (XmlNode) -> Boolean): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(n: XmlNode) {
            if (predicate(n)) out += n
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    fun first(tag: String): XmlNode? = findAll(tag).firstOrNull()

    fun firstText(vararg tags: String): String? =
        tags.firstNotNullOfOrNull { tag -> first(tag)?.text?.takeIf { it.isNotBlank() } }

    /** 자신과 모든 하위 요소의 텍스트를 줄바꿈으로 이어 붙인다. (항·호·목이 따로 들어오는 조문용) */
    fun deepText(): String {
        val parts = mutableListOf<String>()
        fun walk(n: XmlNode) {
            if (n.text.isNotBlank()) parts += n.text
            n.children.forEach(::walk)
        }
        walk(this)
        return parts.joinToString("\n")
    }

    companion object {
        /**
         * SAX로 읽어 트리를 만든다. 안드로이드의 DOM 빌더는 태그 이름 검증이 엄격해서
         * 한글 태그(<키워드> 등)에서 DOMException을 던지므로 DOM 대신 SAX를 쓴다.
         */
        fun parse(xml: String): XmlNode {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val text = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val head = text.take(400)
            if (!text.startsWith("<") || head.startsWith("<!DOCTYPE html", true) || head.startsWith("<html", true)) {
                throw java.io.IOException("법령 서버가 XML이 아닌 응답을 보냈어요: ${snippet(text)}")
            }
            val handler = TreeHandler()
            try {
                factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
            } catch (e: org.xml.sax.SAXException) {
                throw java.io.IOException("법령 서버 응답을 읽지 못했어요(${e.message}): ${snippet(text)}", e)
            }
            return handler.result ?: throw org.xml.sax.SAXException("빈 XML 문서")
        }

        private fun snippet(text: String): String =
            text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { text.take(120) }

        private class Frame(val name: String, val attrs: Map<String, String>) {
            val text = StringBuilder()
            val kids = mutableListOf<XmlNode>()
        }

        private class TreeHandler : DefaultHandler() {
            private val stack = ArrayDeque<Frame>()
            var result: XmlNode? = null

            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                val attrs = buildMap { for (i in 0 until a.length) put(a.getQName(i), a.getValue(i)) }
                stack.addLast(Frame(qName, attrs))
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                stack.lastOrNull()?.text?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val f = stack.removeLast()
                val node = XmlNode(f.name, f.text.toString().trim(), f.attrs, f.kids)
                if (stack.isEmpty()) result = node else stack.last().kids += node
            }
        }
    }
}
