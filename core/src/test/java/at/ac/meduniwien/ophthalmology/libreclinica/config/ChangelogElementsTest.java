/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import liquibase.Scope;
import liquibase.change.ChangeFactory;
import liquibase.precondition.PreconditionFactory;

/**
 * {@link LaxParsingSpringLiquibase} parses the changelog in LAX mode, where
 * Liquibase skips an unknown change type or precondition instead of failing.
 * The seven heritage {@code modifyColumn} elements need that. Nothing else may
 * rely on it: a misspelt change would silently not run, and a misspelt
 * precondition would let its changeset run unguarded. This test makes, for
 * every changelog file, the check that STRICT mode would have made.
 */
public class ChangelogElementsTest {

    /** Skipped by Liquibase 3.6.3 as well; the changesets cannot be edited. */
    private static final Set<String> HERITAGE_UNKNOWN = Set.of(
            "2.5/changeLogCreateTables.xml::235684743487-5-1::modifyColumn",
            "2.5/changeLogCreateTables.xml::235684743487-5-2::modifyColumn",
            "2.5/changeLogCreateTables.xml::235684743487-10-3::modifyColumn",
            "2.5/changeLogCreateTables.xml::235684743487-32-3::modifyColumn",
            "3.6/2015-05-21-OC-5994.xml::2015-05-21-OC-5994-01::modifyColumn",
            "3.6/2015-05-21-OC-5994.xml::2015-05-21-OC-5994-02::modifyColumn",
            "3.6/2015-05-21-OC-5994.xml::2015-05-21-OC-5994-03::modifyColumn");

    /** Changeset children that are not changes. */
    private static final Set<String> CHANGESET_METADATA =
            Set.of("comment", "validCheckSum", "validCheckSums", "modifySql");

    private final Set<String> changes = Scope.getCurrentScope().getSingleton(ChangeFactory.class).getDefinedChanges();
    private final Set<String> preconditions = PreconditionFactory.getInstance().getPreconditions().keySet();

    private final Set<String> unknown = new TreeSet<>();
    private int changeSetCount;

    @Test
    public void theOnlyUnknownElementsAreTheSevenHeritageModifyColumns() throws Exception {
        scanMigrationDirectory();
        assertEquals("the changelog holds a change type or precondition Liquibase does not know; LAX parsing "
                + "would skip it without a word", new TreeSet<>(HERITAGE_UNKNOWN), unknown);
    }

    /** Guard the guard: a scan that finds nothing would pass the test above vacuously. */
    @Test
    public void theScanReadsTheWholeChangelog() throws Exception {
        scanMigrationDirectory();
        assertTrue("expected more than 1,000 changesets, found " + changeSetCount, changeSetCount > 1000);
    }

    private void scanMigrationDirectory() throws Exception {
        Path root = Path.of(getClass().getClassLoader().getResource("migration/master.xml").toURI()).getParent();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(files::add);
        }
        for (Path file : files) {
            Element log = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            if (!"databaseChangeLog".equals(log.getLocalName())) {
                continue;
            }
            String name = root.relativize(file).toString().replace(File.separatorChar, '/');
            for (Element child : children(log)) {
                if ("preConditions".equals(child.getLocalName())) {
                    checkPreconditions(child, name + "::(changelog)");
                } else if ("changeSet".equals(child.getLocalName())) {
                    checkChangeSet(child, name + "::" + child.getAttribute("id"));
                }
            }
        }
    }

    private void checkChangeSet(Element changeSet, String where) {
        changeSetCount++;
        for (Element child : children(changeSet)) {
            String element = child.getLocalName();
            if (CHANGESET_METADATA.contains(element)) {
                continue;
            }
            if ("preConditions".equals(element)) {
                checkPreconditions(child, where);
            } else if ("rollback".equals(element)) {
                for (Element change : children(child)) {
                    checkChange(change, where + "::rollback");
                }
            } else {
                checkChange(child, where);
            }
        }
    }

    private void checkChange(Element change, String where) {
        if (!changes.contains(change.getLocalName())) {
            unknown.add(where + "::" + change.getLocalName());
        }
    }

    private void checkPreconditions(Element container, String where) {
        for (Element child : children(container)) {
            if (!preconditions.contains(child.getLocalName())) {
                unknown.add(where + "::" + child.getLocalName());
            } else if (Set.of("and", "or", "not").contains(child.getLocalName())) {
                checkPreconditions(child, where);
            }
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                elements.add((Element) n);
            }
        }
        return elements;
    }
}
