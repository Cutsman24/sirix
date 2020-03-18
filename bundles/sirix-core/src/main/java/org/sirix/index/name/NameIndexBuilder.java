package org.sirix.index.name;

import org.brackit.xquery.atomic.QNm;
import org.sirix.api.visitor.VisitResultType;
import org.sirix.exception.SirixIOException;
import org.sirix.index.SearchMode;
import org.sirix.index.avltree.AVLTreeReader.MoveCursor;
import org.sirix.index.avltree.AVLTreeWriter;
import org.sirix.index.avltree.keyvalue.NodeReferences;
import org.sirix.node.interfaces.immutable.ImmutableNode;
import org.sirix.utils.LogWrapper;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

public final class NameIndexBuilder {
  private static final LogWrapper LOGGER = new LogWrapper(LoggerFactory.getLogger(NameIndexBuilder.class));

  public Set<QNm> includes;
  public Set<QNm> excludes;
  public AVLTreeWriter<QNm, NodeReferences> avlTreeWriter;

  public NameIndexBuilder(final Set<QNm> includes, final Set<QNm> excludes,
      final AVLTreeWriter<QNm, NodeReferences> avlTreeWriter) {
    this.includes = includes;
    this.excludes = excludes;
    this.avlTreeWriter = avlTreeWriter;
  }

  public VisitResultType build(QNm name, ImmutableNode node) {
    final boolean included = (includes.isEmpty() || includes.contains(name));
    final boolean excluded = (!excludes.isEmpty() && excludes.contains(name));

    if (!included || excluded) {
      return VisitResultType.CONTINUE;
    }

    final Optional<NodeReferences> textReferences = avlTreeWriter.get(name, SearchMode.EQUAL);

    try {
      textReferences.ifPresentOrElse(nodeReferences -> setNodeReferences(node, nodeReferences, name),
          () -> setNodeReferences(node, new NodeReferences(), name));
    } catch (final SirixIOException e) {
      LOGGER.error(e.getMessage(), e);
    }

    return VisitResultType.CONTINUE;
  }

  private void setNodeReferences(final ImmutableNode node, final NodeReferences references, final QNm name) {
    avlTreeWriter.index(name, references.addNodeKey(node.getNodeKey()), MoveCursor.NO_MOVE);
  }
}
