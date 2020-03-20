package org.sirix.index.path.xml;

import org.sirix.api.PageTrx;
import org.sirix.index.IndexDef;
import org.sirix.index.path.PathIndexBuilderFactory;
import org.sirix.index.path.PathIndexListenerFactory;
import org.sirix.index.path.summary.PathSummaryReader;
import org.sirix.node.interfaces.DataRecord;
import org.sirix.page.UnorderedKeyValuePage;

public final class XmlPathIndexImpl implements XmlPathIndex {

  private final PathIndexBuilderFactory pathIndexBuilderFactory;

  private final PathIndexListenerFactory pathIndexListenerFactory;

  public XmlPathIndexImpl() {
    pathIndexBuilderFactory = new PathIndexBuilderFactory();
    pathIndexListenerFactory = new PathIndexListenerFactory();
  }

  @Override
  public XmlPathIndexBuilder createBuilder(final PageTrx<Long, DataRecord, UnorderedKeyValuePage> pageWriteTrx,
      final PathSummaryReader pathSummaryReader, final IndexDef indexDef) {
    final var builderDelegate = pathIndexBuilderFactory.create(pageWriteTrx, pathSummaryReader, indexDef);
    return new XmlPathIndexBuilder(builderDelegate);
  }

  @Override
  public XmlPathIndexListener createListener(final PageTrx<Long, DataRecord, UnorderedKeyValuePage> pageWriteTrx,
      final PathSummaryReader pathSummaryReader, final IndexDef indexDef) {
    final var listenerDelegate = pathIndexListenerFactory.create(pageWriteTrx, pathSummaryReader, indexDef);
    return new XmlPathIndexListener(listenerDelegate);
  }

}
