/*
 * Copyright 2015 The Trustees of the University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.upennlib.marcvalidate;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 *
 * @author magibney
 */
public class Driver implements Runnable {
    
    private static final int SOME_TOO_LARGE_NUMBER = 10;
    private static final String HELP = "USAGE: java -jar file.jar [-h] [-z] [-r] [input-file]\n"
            + "  -h: print this help message\n"
            + "  -z: expect gzipped input\n"
            + "  -r: replace malformed character encoding\n"
            + "  [input-file]: input file; '-' or unspecified for stdin";

    private static void die(String msg) {
        if (msg != null) {
            System.err.println(msg);
        }
        System.err.println(HELP);
        System.exit(1);
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length > SOME_TOO_LARGE_NUMBER) {
            die("too many args");
        }
        Set<String> argSet = new HashSet<>(Arrays.asList(args));
        if (argSet.remove("-h")) {
            die(null);
        }
        final boolean replaceMalformed = argSet.remove("-r");
        final boolean gunzip;
        if (argSet.remove("-z") || (argSet.size() == 1 && argSet.iterator().next().endsWith(".gz"))) {
            gunzip = true;
        } else {
            gunzip = false;
        }
        final String systemId;
        switch (argSet.size()) {
            case 0:
                systemId = null;
                break;
            case 1:
                String arg = argSet.iterator().next();
                systemId = "-".equals(arg) ? null : arg;
                break;
            default:
                die("too many args");
                return;
        }
        InputSource in = new InputSource(systemId);
        InputStream stream = systemId == null ? System.in : new FileInputStream(systemId);
        if (gunzip) {
            stream = new GZIPInputStream(stream);
        }
        if (replaceMalformed) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPLACE);
            decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);
            in.setCharacterStream(new InputStreamReader(stream, decoder));
        } else {
            if (!gunzip) {
                stream = new BufferedInputStream(stream);
            }
            in.setByteStream(stream);
        }
        Driver d = new Driver(in);
        d.run();
    }
    
    private final InputSource in;
    
    private Driver(InputSource in) {
        this.in = in;
    }
    
    @Override
    public void run() {
        TestHandler handler = new TestHandler();
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            spf.setSchema(sf.newSchema(ClassLoader.getSystemResource("MARC21slim.xsd")));
            spf.setValidating(true);
            SAXParser p = spf.newSAXParser();
            p.parse(in, handler);
            System.err.println(handler.good+" good; "+handler.bad + " bad.");
        } catch (SAXException ex) {
            throw new RuntimeException("problem near record "+handler.recordId, ex);
        } catch (ParserConfigurationException ex) {
            throw new RuntimeException("problem near record "+handler.recordId, ex);
        } catch (IOException ex) {
            throw new RuntimeException("problem near record "+handler.recordId, ex);
        }
    }

    private static class TestHandler extends DefaultHandler {

        private int level = -1;
        private boolean inRecordId = false;
        private final StringBuilder recordId = new StringBuilder();
        private final ArrayDeque<String> location = new ArrayDeque<>();
        private final StringBuilder logBuilder = new StringBuilder();
        private final PrintStream out = System.out;
        private int bad = 0;
        private int good = 0;

        private void flush() {
            if (logBuilder.length() > 0) {
                bad++;
                out.println(recordId);
                out.println(logBuilder);
                logBuilder.setLength(0);
            } else {
                good++;
            }
            recordId.setLength(0);
        }

        private void enterRecord(String localName) {
            if ("record".equals(localName)) {
                flush();
            }
        }

        private String getTag(Attributes atts) {
            String tag = atts.getValue("tag");
            return tag == null ? "[no-tag]" : tag;
        }

        private String enterField(String localName, Attributes atts) {
            write();
            String ret;
            switch (localName) {
                case "controlfield":
                    ret = getTag(atts);
                    if ("001".equals(ret)) {
                        inRecordId = true;
                        recordId.setLength(0);
                    }
                    break;
                case "datafield":
                    ret = getTag(atts);
                    break;
                case "leader":
                    ret = "leader";
                    break;
                default:
                    ret = null;
                    break;
            }
            location.push(ret);
            return ret;
        }
        
        private String enterSubfield(String localName, Attributes atts) {
            write();
            String ret;
            if ("subfield".equals(localName)) {
                ret = getTag(atts);
            } else {
                ret = null;
            }
            location.push(ret);
            return ret;
        }
        
        private void exitSubfield() {
            write();
            location.pop();
        }
        
        private void exitField() {
            if (inRecordId) {
                inRecordId = false;
            }
            write();
            location.pop();
        }
        
        private void exitRecord() {
            write();
        }
        
        private void write() {
            if (!exceptions.isEmpty()) {
                logBuilder.append("  ").append(location).append(": ")
                        .append(exceptions).append(System.lineSeparator());
                exceptions.clear();
            }
        }
        
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            switch (level) {
                case 0:
                    enterRecord(localName);
                    break;
                case 1:
                    enterField(localName, attributes);
                    break;
                case 2:
                    enterSubfield(localName, attributes);
                    break;
            }
            super.startElement(uri, localName, qName, attributes);
            level++;
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            level--;
            super.endElement(uri, localName, qName);
            switch (level) {
                case 0:
                    exitRecord();
                    break;
                case 1:
                    exitField();
                    break;
                case 2:
                    exitSubfield();
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            super.characters(ch, start, length);
            if (inRecordId) {
                recordId.append(ch, start, length);
            }
        }

        private final Set<String> exceptions = new HashSet<>();
        
        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            exceptions.add(e.getMessage());
            super.fatalError(e);
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            exceptions.add(e.getMessage());
            super.error(e);
        }

        @Override
        public void warning(SAXParseException e) throws SAXException {
            exceptions.add(e.getMessage());
            super.warning(e);
        }
        
    }
    
}
