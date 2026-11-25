package com.rafaelgpq.depguardian.report;

import com.rafaelgpq.depguardian.model.DependencyModel.*;
import com.rafaelgpq.depguardian.input.DependencyTreeReader.*;

import java.io.StringWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Opt-in, informational dependencyManagement candidates, one fragment per module. The highest seen version is not
 * Maven's selection and not a verified compatible fix; review before use. Never applied to any POM automatically.
 */
public final class DependencyManagementXml {

    private DependencyManagementXml() {}

    public static String render(AnalysisResult analysis) {
        StringWriter buffer = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(buffer);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("candidates");
            xml.writeCharacters("\n");
            xml.writeComment(" Informational: highest seen version per conflict; not verified, human review required. ");
            xml.writeCharacters("\n");
            for (ModuleAnalysis module : analysis.modules()) {
                if (module.conflicts().isEmpty()) continue;
                xml.writeCharacters("  ");
                xml.writeStartElement("module");
                xml.writeAttribute("coordinate", module.root().toString());
                xml.writeCharacters("\n    ");
                xml.writeStartElement("dependencyManagement");
                xml.writeCharacters("\n      ");
                xml.writeStartElement("dependencies");
                xml.writeCharacters("\n");
                for (ChildFinding finding : module.conflicts()) {
                    ArtifactIdentity id = finding.identity();
                    xml.writeCharacters("        ");
                    xml.writeStartElement("dependency");
                    xml.writeCharacters("\n");
                    element(xml, "groupId", id.groupId());
                    element(xml, "artifactId", id.artifactId());
                    element(xml, "version", finding.candidateVersion());
                    if (!"jar".equals(id.type())) element(xml, "type", id.type());
                    if (!id.classifier().isEmpty()) element(xml, "classifier", id.classifier());
                    xml.writeCharacters("        ");
                    xml.writeEndElement();
                    xml.writeCharacters("\n");
                }
                xml.writeCharacters("      ");
                xml.writeEndElement();
                xml.writeCharacters("\n    ");
                xml.writeEndElement();
                xml.writeCharacters("\n  ");
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }
            xml.writeEndElement();
            xml.writeCharacters("\n");
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot write dependencyManagement XML", e);
        }
        return buffer.toString();
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeCharacters("          ");
        xml.writeStartElement(name);
        xml.writeCharacters(text);
        xml.writeEndElement();
        xml.writeCharacters("\n");
    }
}
