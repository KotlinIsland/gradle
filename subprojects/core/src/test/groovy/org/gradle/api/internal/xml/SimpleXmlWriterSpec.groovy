/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.xml

import spock.lang.Specification

import javax.xml.parsers.DocumentBuilderFactory

/**
 * by Szczepan Faber, created at: 12/3/12
 */
class SimpleXmlWriterSpec extends Specification {

    private sw = new ByteArrayOutputStream()
    private writer = new SimpleXmlWriter(sw)

    String getXml() {
        def text = sw.toString("UTF-8")
        println "TEXT {$text}"
        def document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(sw.toByteArray()))
        assert document
        return text
    }

    def "writes basic XML"() {
        when:
        writer.startElement("root").attribute("items", "9")
        writer.startElement("item").endElement()
        writer.startElement("item").attribute("size", "10m")
        writer.characters("some chars")
        writer.characters(" and some other".toCharArray())
        writer.characters("x  chars.x".toCharArray(), 2, 7)
        writer.startElement("foo").characters(" ")
        writer.endElement()
        writer.endElement()
        writer.endElement()

        then:
        xml == '<?xml version="1.1" encoding="UTF-8"?><root items="9"><item/><item size="10m">some chars and some other chars.<foo> </foo></item></root>'
    }

    def "escapes reserved characters in text content"() {
        when:
        writer.startElement("root")
        writer.characters("chars with interesting stuff: &lt; < > ' \" ]]> \r\n \t")
        writer.endElement()

        then:
        xml.contains('<root>chars with interesting stuff: &amp;lt; &lt; &gt; \' &quot; ]]&gt; \r\n \t</root>')
    }

    def "escapes reserved characters in attribute values"() {
        when:
        writer.startElement("root")
        writer.startElement("item").attribute("description", "encoded: \t &lt; < > ' \n\r\"  ")
        writer.endElement()
        writer.endElement()

        then:
        xml.contains('<item description="encoded: &#9; &amp;lt; &lt; &gt; \' &#10;&#13;&quot;  "/>')

        and:
        def item = new XmlSlurper().parseText(xml).item
        item.@description.text() == "encoded: \t &lt; < > ' \n\r\"  "
    }

    def "writes CDATA"() {
        when:
        writer.startElement("root")
        writer.startElement("stuff")

        writer.startCDATA()
        writer.characters('x hey x'.toCharArray(), 2, 4)
        writer.characters('joe'.toCharArray())
        writer.characters("!")
        writer.endCDATA()

        writer.endElement()

        writer.startCDATA()
        writer.characters('encodes: ]]> ')
        writer.characters('does not encode: ]] ')
        writer.characters('html allowed: <> &amp;')
        writer.endCDATA()

        writer.endElement()

        then:
        xml.contains('<stuff><![CDATA[hey joe!]]></stuff><![CDATA[encodes: ]]]]><![CDATA[> does not encode: ]] html allowed: <> &amp;]]>')
    }

    def "encodes CDATA when token on the border"() {
        when:
        //the end token is on the border of both char arrays
        writer.startElement('root')
        writer.startCDATA()
        writer.characters('stuff ]]')
        writer.characters('> more stuff')
        writer.endCDATA()
        writer.endElement()

        then:
        xml.contains('<![CDATA[stuff ]]]]><![CDATA[> more stuff]]>')
    }

    def "does not encode CDATA when token separated in different CDATAs"() {
        when:
        //the end token is on the border of both char arrays

        writer.startElement('root')

        writer.startCDATA();
        writer.characters('stuff ]]')
        writer.endCDATA();

        writer.startCDATA()
        writer.characters('> more stuff')
        writer.endCDATA();

        writer.endElement()

        then:
        xml.contains('<root><![CDATA[stuff ]]]]><![CDATA[> more stuff]]></root>')
    }

    def "encodes non-ASCII characters"() {
        when:
        writer.startElement("\u0200").attribute("\u0201", "\u0202")
        writer.characters("\u0203")
        writer.startCDATA().characters("\u0204").endCDATA()
        writer.endElement()

        then:
        xml.contains('<\u0200 \u0201="\u0202">\u0203<![CDATA[\u0204]]></\u0200>')
    }

    def "escapes restricted characters in text content"() {
        when:
        writer.startElement("root")
        writer.attribute("name", "\u0084\u0002")
        writer.characters("\u0084\u0002\u009f")
        writer.startCDATA().characters("\u0084\u0002").endCDATA()
        writer.endElement()

        then:
        xml.contains('<root name="&#x84;&#x2;">&#x84;&#x2;&#x9f;<![CDATA[]]>&#x84;<![CDATA[]]>&#x2;<![CDATA[]]></root>')
    }

    def "validates characters in text content"() {
        given:
        writer.startElement("root")

        when:
        writer.characters(chars)

        then:
        IllegalArgumentException e = thrown()
        e.message.startsWith("Illegal XML character 0x")

        when:
        writer.startElement("broken").attribute("name", chars)

        then:
        e = thrown()
        e.message.startsWith("Illegal XML character 0x")

        when:
        writer.startCDATA().characters(chars)

        then:
        e = thrown()
        e.message.startsWith("Illegal XML character 0x")

        where:
        chars << ["\u0000", "\ud800", "\udfff", "\ufffe"]
    }

    def "is a Writer implementation that escapes characters"() {
        when:
        writer.startElement("root")
        writer.write("some <chars>")
        writer.write(" and ".toCharArray())
        writer.write("x some x".toCharArray(), 2, 4)
        writer.write(' ')
        writer.startCDATA()
        writer.write("cdata")
        writer.endCDATA()
        writer.endElement()

        then:
        xml.contains("<root>some &lt;chars&gt; and some <![CDATA[cdata]]></root>")
    }

    def "cannot end element when stack is empty"() {
        writer.startElement("root")
        writer.endElement()

        when:
        writer.endElement()

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot end element, as there are no started elements.'
    }

    def "cannot end element when CDATA node is open"() {
        writer.startElement("root")
        writer.startCDATA()

        when:
        writer.endElement()

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot end element, as current CDATA node has not been closed.'
    }

    def "cannot start element when CDATA node is open"() {
        writer.startElement("root")
        writer.startCDATA()

        when:
        writer.startElement("nested")

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot start element, as current CDATA node has not been closed.'
    }

    def "cannot start CDATA node when CDATA node is open"() {
        writer.startElement("root")
        writer.startCDATA()

        when:
        writer.startCDATA()

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot start CDATA node, as current CDATA node has not been closed.'
    }

    def "cannot end CDATA node when not in a CDATA node"() {
        writer.startElement("root")

        when:
        writer.endCDATA()

        then:
        IllegalStateException e = thrown()
        e.message == 'Cannot end CDATA node, as not currently in a CDATA node.'
    }

    def "closes tags"() {
        when:
        writer.startElement("root")
        action.call(writer)
        writer.endElement()

        then:
        sw.toString().contains("<root>") //is closed with '>'

        where:
        action << [{ it.startElement("foo"); it.endElement() },
                { it.startCDATA(); it.endCDATA() },
                { it.characters("bar") },
                { it.write("close") }]
    }

    def "closes attributed tags"() {
        when:
        writer.startElement("root")
        writer.attribute("foo", '115')
        action.call(writer)
        writer.endElement()

        then:
        sw.toString().contains('<root foo="115">') //is closed with '>'

        where:
        action << [{ it.startElement("foo"); it.endElement() },
                { it.startCDATA(); it.endCDATA() },
                { it.characters("bar") },
                { it.write("close") }]
    }

    def "outputs empty element when element has no content"() {
        when:
        writer.startElement("root")
        writer.startElement("empty").endElement()
        writer.startElement("empty").attribute("property", "value").endElement()
        writer.endElement()

        then:
        xml.contains('<root><empty/><empty property="value"/></root>')
    }

    def "allows valid tag names"() {
        when:
        writer.startElement(name)

        then:
        notThrown(IllegalArgumentException)

        where:
        name << ["name", "NAME", "with-dashes", "with.dots", "with123digits", ":", "_", "\u037f\u0300"]
    }

    def "validates tag names"() {
        when:
        writer.startElement(name)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Invalid element name: '$name'"

        where:
        name << ["tag with space", "", "-invalid-start-char", "  ", "912", "\u00d7"]
    }

    def "allows valid attribute names"() {
        when:
        writer.startElement("foo").attribute(name, "foo")

        then:
        notThrown(IllegalArgumentException)

        where:
        name << ["name", "NAME", "with-dashes", "with.dots", "with123digits", ":", "_", "\u037f\u0300"]
    }

    def "validates attribute names"() {
        when:
        writer.startElement("foo").attribute(name, "foo")

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Invalid attribute name: '$name'"

        where:
        name << ["attribute with space", "", "-invalid-start-char", "  ", "912", "\u00d7"]
    }
}