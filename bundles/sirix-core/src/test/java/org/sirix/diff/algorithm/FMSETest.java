package org.sirix.diff.algorithm;

import static java.util.stream.Collectors.toList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.custommonkey.xmlunit.DetailedDiff;
import org.custommonkey.xmlunit.Diff;
import org.custommonkey.xmlunit.Difference;
import org.custommonkey.xmlunit.XMLTestCase;
import org.custommonkey.xmlunit.XMLUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sirix.TestHelper;
import org.sirix.TestHelper.PATHS;
import org.sirix.access.conf.ResourceManagerConfiguration;
import org.sirix.api.Database;
import org.sirix.api.ResourceManager;
import org.sirix.api.XdmNodeWriteTrx;
import org.sirix.diff.service.FMSEImport;
import org.sirix.exception.SirixException;
import org.sirix.service.xml.serialize.XMLSerializer;
import org.sirix.service.xml.serialize.XMLSerializer.XMLSerializerBuilder;
import org.sirix.service.xml.shredder.Insert;
import org.sirix.service.xml.shredder.XMLShredder;

/**
 * Test the FMSE implementation.
 *
 * @author Johannes Lichtenberger, University of Konstanz
 */
public final class FMSETest extends XMLTestCase {
  private static final String RESOURCES =
      "src" + File.separator + "test" + File.separator + "resources";

  private static final String XMLINSERTFIRST = RESOURCES + File.separator + "revXMLsInsert";

  private static final String XMLINSERTSECOND = RESOURCES + File.separator + "revXMLsInsert1";

  private static final String XMLINSERTTHIRD = RESOURCES + File.separator + "revXMLsInsert2";

  private static final String XMLDELETEFIRST = RESOURCES + File.separator + "revXMLsDelete";

  private static final String XMLDELETESECOND = RESOURCES + File.separator + "revXMLsDelete1";

  private static final String XMLDELETETHIRD = RESOURCES + File.separator + "revXMLsDelete2";

  private static final String XMLDELETEFOURTH = RESOURCES + File.separator + "revXMLsDelete3";

  private static final String XMLSAMEFIRST = RESOURCES + File.separator + "revXMLsSame";

  private static final String XMLSAMESECOND = RESOURCES + File.separator + "revXMLsSame1";

  private static final String XMLALLFIRST = RESOURCES + File.separator + "revXMLsAll";

  private static final String XMLALLSECOND = RESOURCES + File.separator + "revXMLsAll1";

  private static final String XMLALLTHIRD = RESOURCES + File.separator + "revXMLsAll2";

  private static final String XMLALLFOURTH = RESOURCES + File.separator + "revXMLsAll3";

  private static final String XMLALLFIFTH = RESOURCES + File.separator + "revXMLsAll4";

  private static final String XMLALLSIXTH = RESOURCES + File.separator + "revXMLsAll5";

  private static final String XMLALLSEVENTH = RESOURCES + File.separator + "revXMLsAll6";

  private static final String XMLALLEIGHTH = RESOURCES + File.separator + "revXMLsAll7";

  private static final String XMLALLNINETH = RESOURCES + File.separator + "revXMLsAll8";

  private static final String XMLALLTENTH = RESOURCES + File.separator + "revXMLsAll9";

  private static final String XMLALLELEVENTH = RESOURCES + File.separator + "revXMLsAll10";

  private static final String XMLLINGUISTICS = RESOURCES + File.separator + "linguistics";

  static {
    XMLUnit.setIgnoreComments(true);
    XMLUnit.setIgnoreWhitespace(true);
  }

  @Override
  @Before
  public void setUp() throws SirixException {
    TestHelper.deleteEverything();
  }

  @Override
  @After
  public void tearDown() throws SirixException, IOException {
    TestHelper.closeEverything();
  }

  @Test
  public void testAllFirst() throws Exception {
    test(XMLALLFIRST);
  }

  @Test
  public void testAllSecond() throws Exception {
    test(XMLALLSECOND);
  }

  @Test
  public void testAllThird() throws Exception {
    test(XMLALLTHIRD);
  }

  @Test
  public void testAllFourth() throws Exception {
    test(XMLALLFOURTH);
  }

  @Test
  public void testAllFifth() throws Exception {
    test(XMLALLFIFTH);
  }

  @Test
  public void testAllSixth() throws Exception {
    test(XMLALLSIXTH);
  }

  @Test
  public void testAllSeventh() throws Exception {
    test(XMLALLSEVENTH);
  }

  @Test
  public void testAllEigth() throws Exception {
    test(XMLALLEIGHTH);
  }

  @Test
  public void testAllNineth() throws Exception {
    test(XMLALLNINETH);
  }

  @Test
  public void testAllTenth() throws Exception {
    test(XMLALLTENTH);
  }

  @Test
  public void testAllEleventh() throws Exception {
    test(XMLALLELEVENTH);
  }

  @Test
  public void testDeleteFirst() throws Exception {
    test(XMLDELETEFIRST);
  }

  @Test
  public void testDeleteSecond() throws Exception {
    test(XMLDELETESECOND);
  }

  @Test
  public void testDeleteThird() throws Exception {
    test(XMLDELETETHIRD);
  }

  @Test
  public void testDeleteFourth() throws Exception {
    test(XMLDELETEFOURTH);
  }

  @Test
  public void testSameFirst() throws Exception {
    test(XMLSAMEFIRST);
  }

  @Test
  public void testSameSecond() throws Exception {
    test(XMLSAMESECOND);
  }

  @Test
  public void testInsertFirst() throws Exception {
    test(XMLINSERTFIRST);
  }

  @Test
  public void testInsertSecond() throws Exception {
    test(XMLINSERTSECOND);
  }

  @Test
  public void testInsertThird() throws Exception {
    test(XMLINSERTTHIRD);
  }

  @Test
  public void testLinguistics() throws Exception {
    test(XMLLINGUISTICS);
  }

  /**
   * Test a folder of XML files.
   *
   * @param FOLDER path string
   * @throws Exception if any exception occurs
   */
  private void test(final String FOLDER) throws Exception {
    Database database = TestHelper.getDatabase(PATHS.PATH1.getFile());
    ResourceManager resource = database
        .getResourceManager(new ResourceManagerConfiguration.Builder(TestHelper.RESOURCE).build());
    final Path folder = Paths.get(FOLDER);
    final List<Path> list =
        Files.list(folder).filter(path -> path.getFileName().endsWith(".xml")).collect(toList());

    // Sort files list according to file names.
    list.sort((first, second) -> {
      final String firstName =
          first.getFileName().toString().substring(0, first.getFileName().toString().indexOf('.'));
      final String secondName = second.getFileName().toString().substring(0,
          second.getFileName().toString().indexOf('.'));

      if (Integer.parseInt(firstName) < Integer.parseInt(secondName)) {
        return -1;
      } else if (Integer.parseInt(firstName) > Integer.parseInt(secondName)) {
        return +1;
      } else {
        return 0;
      }
    });

    boolean first = true;

    // Shredder files.
    for (final Path file : list) {
      if (file.getFileName().endsWith(".xml")) {
        if (first) {
          first = false;
          try (final XdmNodeWriteTrx wtx = resource.beginNodeWriteTrx()) {
            final XMLShredder shredder = new XMLShredder.Builder(wtx,
                XMLShredder.createFileReader(file), Insert.ASFIRSTCHILD).commitAfterwards().build();
            shredder.call();
          }
        } else {
          FMSEImport.main(new String[] {PATHS.PATH1.getFile().toAbsolutePath().toString(),
              file.toAbsolutePath().toString()});
        }

        resource.close();
        resource = database.getResourceManager(
            new ResourceManagerConfiguration.Builder(TestHelper.RESOURCE).build());

        final OutputStream out = new ByteArrayOutputStream();
        final XMLSerializer serializer = new XMLSerializerBuilder(resource, out).build();
        serializer.call();
        final StringBuilder sBuilder = TestHelper.readFile(file, false);

        final Diff diff = new Diff(sBuilder.toString(), out.toString());
        final DetailedDiff detDiff = new DetailedDiff(diff);
        @SuppressWarnings("unchecked")
        final List<Difference> differences = detDiff.getAllDifferences();
        for (final Difference difference : differences) {
          System.err.println("***********************");
          System.err.println(difference);
          System.err.println("***********************");
        }

        assertTrue("pieces of XML are similar " + diff, diff.similar());
        assertTrue("but are they identical? " + diff, diff.identical());
      }
    }

    database.close();
  }
}
