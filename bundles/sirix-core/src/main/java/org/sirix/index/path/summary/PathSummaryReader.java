package org.sirix.index.path.summary;

import com.google.common.base.MoreObjects;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.util.path.Path;
import org.brackit.xquery.util.path.PathException;
import org.sirix.access.User;
import org.sirix.access.trx.node.CommitCredentials;
import org.sirix.api.*;
import org.sirix.api.json.JsonResourceManager;
import org.sirix.axis.DescendantAxis;
import org.sirix.axis.IncludeSelf;
import org.sirix.axis.filter.FilterAxis;
import org.sirix.axis.filter.PathNameFilter;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.node.NodeKind;
import org.sirix.node.NullNode;
import org.sirix.node.immutable.json.ImmutableJsonDocumentRootNode;
import org.sirix.node.immutable.xml.ImmutableXmlDocumentRootNode;
import org.sirix.node.interfaces.NameNode;
import org.sirix.node.interfaces.Record;
import org.sirix.node.interfaces.StructNode;
import org.sirix.node.interfaces.immutable.ImmutableNode;
import org.sirix.node.json.JsonDocumentRootNode;
import org.sirix.node.xml.XmlDocumentRootNode;
import org.sirix.page.PageKind;
import org.sirix.page.PathSummaryPage;
import org.sirix.settings.Fixed;
import org.sirix.utils.NamePageHash;

import javax.annotation.Nonnegative;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Path summary reader organizing the path classes of a resource.
 *
 * @author Johannes Lichtenberger, University of Konstanz
 */
public final class PathSummaryReader implements NodeReadOnlyTrx, NodeCursor {

  /**
   * Strong reference to currently selected node.
   */
  private StructNode currentNode;

  /**
   * Page reader.
   */
  private final PageReadOnlyTrx pageReadOnlyTrx;

  /**
   * {@link ResourceManager} reference.
   */
  private final ResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> resourceManager;

  /**
   * Determines if path summary is closed or not.
   */
  private boolean isClosed;

  /**
   * Mapping of a path node key to the path node/document root node.
   */
  private final Map<Long, StructNode> pathNodeMapping;

  /**
   * Mapping of a {@link QNm} to a set of path nodes.
   */
  private final Map<QNm, Set<PathNode>> nameToPathMapping;

  /**
   * The path cache.
   */
  private final Map<Path<QNm>, Set<Long>> pathCache;

  private boolean init;

  /**
   * Private constructor.
   *
   * @param pageReadTrx     page reader
   * @param resourceManager {@link ResourceManager} reference
   */
  private PathSummaryReader(final PageReadOnlyTrx pageReadTrx,
      final ResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> resourceManager) {
    this.pathCache = new HashMap<>();
    this.pageReadOnlyTrx = pageReadTrx;
    this.isClosed = false;
    this.resourceManager = resourceManager;

    final Optional<? extends Record> node =
        pageReadOnlyTrx.getRecord(Fixed.DOCUMENT_NODE_KEY.getStandardProperty(), PageKind.PATHSUMMARYPAGE, 0);
    if (node.isPresent()) {
      currentNode = (StructNode) node.get();
    } else {
      throw new IllegalStateException("Node couldn't be fetched from persistent storage!");
    }

    pathNodeMapping = new HashMap<>();
    nameToPathMapping = new HashMap<>();
    boolean first = true;
    for (final long nodeKey : new DescendantAxis(this, IncludeSelf.YES)) {
      pathNodeMapping.put(nodeKey, this.getStructuralNode());

      if (first) {
        first = false;
      } else {
        final Set<PathNode> pathNodes =
            nameToPathMapping.get(this.getName()) == null ? new HashSet<>() : nameToPathMapping.get(this.getName());
        pathNodes.add(this.getPathNode());
        nameToPathMapping.put(this.getName(), pathNodes);
      }
    }

    init = false;
  }

  @Override
  public Optional<User> getUser() {
    return pageReadOnlyTrx.getActualRevisionRootPage().getUser();
  }

  @Override
  public PageReadOnlyTrx getPageTrx() {
    return pageReadOnlyTrx;
  }

  /**
   * Get a new path summary reader instance.
   *
   * @param pageReadTrx     the {@link PageReadOnlyTrx} instance
   * @param resourceManager the {@link ResourceManager} instance
   * @return new path summary reader instance
   */
  public static PathSummaryReader getInstance(final PageReadOnlyTrx pageReadTrx,
      final ResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> resourceManager) {
    return new PathSummaryReader(checkNotNull(pageReadTrx), checkNotNull(resourceManager));
  }

  // package private, only used in writer to keep the mapping always up-to-date
  void putMapping(final @Nonnegative
      long pathNodeKey, final StructNode node) {
    pathNodeMapping.put(pathNodeKey, node);
  }

  // package private, only used in writer to keep the mapping always up-to-date
  StructNode removeMapping(final @Nonnegative
      long pathNodeKey) {
    return pathNodeMapping.remove(pathNodeKey);
  }

  // package private, only used in writer to keep the mapping always up-to-date
  void putQNameMapping(final PathNode node, final QNm name) {
    final Set<PathNode> pathNodes = nameToPathMapping.get(name) == null ? new HashSet<>() : nameToPathMapping.get(name);
    pathNodes.add(node);
    nameToPathMapping.put(name, pathNodes);
  }

  // package private, only used in writer to keep the mapping always up-to-date
  void removeQNameMapping(final @Nonnegative
      PathNode node, final QNm name) {
    final Set<PathNode> pathNodes = nameToPathMapping.get(name) == null ? new HashSet<>() : nameToPathMapping.get(name);
    if (pathNodes.size() == 1) {
      nameToPathMapping.remove(name);
    } else {
      pathNodes.remove(node);
    }
  }

  /**
   * Match all descendants of the node denoted by its {@code pathNodeKey} with the given {@code name}.
   *
   * @param name        the QName
   * @param pathNodeKey the path node key to start the search from
   * @param includeSelf if current node should be included or not
   * @return a set with bits set for each matching path node (its {@code pathNodeKey})
   */
  public BitSet matchDescendants(final QNm name, final @Nonnegative
      long pathNodeKey, final IncludeSelf includeSelf) {
    assertNotClosed();
    final Set<PathNode> set = nameToPathMapping.get(name);
    if (set == null) {
      return new BitSet(0);
    }
    moveTo(pathNodeKey);
    final BitSet matches = new BitSet();
    for (final long nodeKey : new FilterAxis<>(new DescendantAxis(this, includeSelf),
        new PathNameFilter(this, name.toString()))) {
      matches.set((int) nodeKey);
    }
    return matches;
  }

  /**
   * Match a {@link QNm} with a minimum level.
   *
   * @param name     the QName
   * @param minLevel minimum level
   * @return a set with bits set for each matching path node
   */
  public BitSet match(final QNm name, final @Nonnegative
      int minLevel) {
    assertNotClosed();
    final Set<PathNode> set = nameToPathMapping.get(name);
    if (set == null) {
      return new BitSet(0);
    }
    final BitSet matches = new BitSet();
    for (final PathNode psn : set) {
      if (psn.getLevel() >= minLevel) {
        matches.set((int) psn.getNodeKey());
      }
    }
    return matches;
  }

  /**
   * Get a set of PCRs matching the specified collection of paths
   *
   * @param expressions the paths to lookup
   * @param useCache    determines if the cache can be used or not
   * @return a set of PCRs matching the specified collection of paths
   * @throws SirixException if parsing a path fails
   */
  public Set<Long> getPCRsForPaths(final Collection<Path<QNm>> expressions, final boolean useCache)
      throws PathException {
    assertNotClosed();
    final Set<Long> pcrs = new HashSet<>();
    for (final Path<QNm> path : expressions) {
      final Set<Long> pcrsForPath = getPCRsForPath(path, useCache);
      pcrs.addAll(pcrsForPath);
    }
    return pcrs;
  }

  /**
   * Get the path node corresponding to the key.
   *
   * @param pathNodeKey path node key
   * @return path node corresponding to the provided key
   */
  public PathNode getPathNodeForPathNodeKey(final @Nonnegative
      long pathNodeKey) {
    assertNotClosed();

    if (pathNodeKey < 0) {
      throw new IllegalArgumentException("Key not supported.");
    } else if (pathNodeKey == 0) {
      return null;
    }
    return (PathNode) pathNodeMapping.get(pathNodeKey);
  }

  @Override
  public ImmutableNode getNode() {
    assertNotClosed();
    if (currentNode instanceof XmlDocumentRootNode) {
      return ImmutableXmlDocumentRootNode.of((XmlDocumentRootNode) currentNode);
    } else if (currentNode instanceof JsonDocumentRootNode) {
      return ImmutableJsonDocumentRootNode.of((JsonDocumentRootNode) currentNode);
    }
    return ImmutablePathNode.of((PathNode) currentNode);
  }

  /**
   * Get path class records (PCRs) for the specified path.
   *
   * @param path     the path for which to get a set of PCRs
   * @param useCache determines if the path cache can be used or not
   * @return set of PCRs belonging to the specified path
   * @throws SirixException if anything went wrong
   */
  public Set<Long> getPCRsForPath(final Path<QNm> path, final boolean useCache) throws PathException {
    final Set<Long> pcrSet;
    if (useCache) {
      if (pathCache.containsKey(path) && pathCache.get(path) != null) {
        return pathCache.get(path);
      } else {
        pcrSet = new HashSet<>();
      }
    } else {
      pcrSet = new HashSet<>();
    }

    final boolean isAttributePattern = path.isAttribute();
    final int pathLength = path.getLength();

    final long nodeKey = currentNode.getNodeKey();
    moveToDocumentRoot();
    for (final Axis axis = new DescendantAxis(this); axis.hasNext(); ) {
      axis.next();
      final PathNode node = this.getPathNode();

      if (node == null) {
        continue;
      }

      if (node.getLevel() < pathLength) {
        continue;
      }

      if (isAttributePattern ^ (node.getPathKind() == NodeKind.ATTRIBUTE)) {
        continue;
      }

      if (path.matches(node.getPath(this))) {
        pcrSet.add(node.getNodeKey());
      }
    }
    moveTo(nodeKey);
    if (useCache) {
      pathCache.put(path, pcrSet);
    }
    return pcrSet;
  }

  @Override
  public boolean hasChildren() {
    assertNotClosed();
    return getStructuralNode().getChildCount() > 0;
  }

  /**
   * Get a path node.
   *
   * @return {@link PathNode} reference or null for the document root.
   */
  public PathNode getPathNode() {
    assertNotClosed();
    if (currentNode instanceof PathNode) {
      return (PathNode) currentNode;
    } else {
      return null;
    }
  }

  @Override
  public Move<? extends PathSummaryReader> moveTo(final long nodeKey) {
    assertNotClosed();

    if (!init) {
      final PathNode node = getPathNodeForPathNodeKey(nodeKey);

      if (node != null) {
        currentNode = node;
        return Move.moved(this);
      }
    }

    // Remember old node and fetch new one.
    final StructNode oldNode = currentNode;
    Optional<? extends StructNode> newNode;
    try {
      // Immediately return node from item list if node key negative.
      @SuppressWarnings("unchecked")
      final Optional<? extends StructNode> node =
          (Optional<? extends StructNode>) pageReadOnlyTrx.getRecord(nodeKey, PageKind.PATHSUMMARYPAGE, 0);
      newNode = node;
    } catch (final SirixIOException e) {
      newNode = Optional.empty();
    }

    if (newNode.isPresent()) {
      currentNode = newNode.get();
      return Move.moved(this);
    } else {
      currentNode = oldNode;
      return Move.notMoved();
    }
  }

  @Override
  public Move<? extends PathSummaryReader> moveToParent() {
    assertNotClosed();
    return moveTo(getStructuralNode().getParentKey());
  }

  @Override
  public Move<? extends PathSummaryReader> moveToFirstChild() {
    assertNotClosed();
    if (!getStructuralNode().hasFirstChild()) {
      return Move.notMoved();
    }
    return moveTo(getStructuralNode().getFirstChildKey());
  }

  @Override
  public Move<? extends PathSummaryReader> moveToLeftSibling() {
    assertNotClosed();
    if (!getStructuralNode().hasLeftSibling()) {
      return Move.notMoved();
    }
    return moveTo(getStructuralNode().getLeftSiblingKey());
  }

  @Override
  public Move<? extends PathSummaryReader> moveToRightSibling() {
    assertNotClosed();
    if (!getStructuralNode().hasRightSibling()) {
      return Move.notMoved();
    }
    return moveTo(getStructuralNode().getRightSiblingKey());
  }

  @Override
  public void close() {
    if (!isClosed) {
      // Immediately release all references.
      currentNode = null;
      isClosed = true;

      if (pageReadOnlyTrx != null && !pageReadOnlyTrx.isClosed()) {
        pageReadOnlyTrx.close();
      }
    }
  }

  /**
   * Make sure that the path summary is not yet closed when calling this method.
   */
  final void assertNotClosed() {
    if (isClosed) {
      throw new IllegalStateException("Path summary is already closed.");
    }
  }

  @Override
  public Move<? extends PathSummaryReader> moveToDocumentRoot() {
    return moveTo(Fixed.DOCUMENT_NODE_KEY.getStandardProperty());
  }

  /**
   * Get the current node as a structural node.
   *
   * @return structural node
   */
  private StructNode getStructuralNode() {
    if (currentNode instanceof StructNode) {
      return currentNode;
    } else {
      return new NullNode(currentNode);
    }
  }

  @Override
  public long getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getRevisionNumber() {
    assertNotClosed();
    return pageReadOnlyTrx.getRevisionNumber();
  }

  @Override
  public Instant getRevisionTimestamp() {
    assertNotClosed();
    return Instant.ofEpochMilli(pageReadOnlyTrx.getActualRevisionRootPage().getRevisionTimestamp());
  }

  @Override
  public long getMaxNodeKey() {
    assertNotClosed();
    return ((PathSummaryPage) pageReadOnlyTrx.getActualRevisionRootPage()
                                             .getPathSummaryPageReference()
                                             .getPage()).getMaxNodeKey(0);
  }

  @Override
  public Move<? extends PathSummaryReader> moveToNextFollowing() {
    assertNotClosed();
    while (!getStructuralNode().hasRightSibling() && currentNode.hasParent()) {
      moveToParent();
    }
    return moveToRightSibling();
  }

  @Override
  public QNm getName() {
    assertNotClosed();
    if (currentNode instanceof NameNode) {
      final int uriKey = ((NameNode) currentNode).getURIKey();
      final String uri = uriKey == -1 || pageReadOnlyTrx.getResourceManager() instanceof JsonResourceManager
          ? ""
          : pageReadOnlyTrx.getName(((NameNode) currentNode).getURIKey(), NodeKind.NAMESPACE);
      final int prefixKey = ((NameNode) currentNode).getPrefixKey();
      final String prefix =
          prefixKey == -1 ? "" : pageReadOnlyTrx.getName(prefixKey, ((PathNode) currentNode).getPathKind());
      final int localNameKey = ((NameNode) currentNode).getLocalNameKey();
      final String localName =
          localNameKey == -1 ? "" : pageReadOnlyTrx.getName(localNameKey, ((PathNode) currentNode).getPathKind());
      return new QNm(uri, prefix, localName);
    } else {
      return null;
    }
  }

  @Override
  public int keyForName(final String pName) {
    assertNotClosed();
    return NamePageHash.generateHashForString(pName);
  }

  @Override
  public String nameForKey(final int key) {
    assertNotClosed();
    if (currentNode instanceof PathNode) {
      final PathNode node = (PathNode) currentNode;
      return pageReadOnlyTrx.getName(key, node.getPathKind());
    } else {
      return "";
    }
  }

  @Override
  public boolean isClosed() {
    return isClosed;
  }

  @Override
  public ResourceManager<? extends NodeReadOnlyTrx, ? extends NodeTrx> getResourceManager() {
    assertNotClosed();
    return resourceManager;
  }

  @Override
  public Move<? extends PathSummaryReader> moveToLastChild() {
    assertNotClosed();
    if (getStructuralNode().hasFirstChild()) {
      moveToFirstChild();

      while (getStructuralNode().hasRightSibling()) {
        moveToRightSibling();
      }

      return Move.moved(this);
    }
    return Move.notMoved();
  }

  /**
   * Get the path up to the root path node.
   *
   * @return path up to the root
   */
  public Path<QNm> getPath() {
    PathNode node = getPathNode();
    if (node == null) {
      moveToFirstChild();
      node = getPathNode();

      if (node == null) {
        return null;
      }
    }
    final long nodeKey = getNodeKey();
    moveTo(node.getNodeKey());
    final PathNode[] paths = new PathNode[node.getLevel()];
    for (int i = node.getLevel() - 1; i >= 0; i--) {
      paths[i] = node;
      node = moveToParent().trx().getPathNode();
    }

    final Path<QNm> path = new Path<>();
    for (final PathNode pathNode : paths) {
      moveTo(pathNode.getNodeKey());
      if (pathNode.getPathKind() == NodeKind.ATTRIBUTE) {
        path.attribute(getName());
      } else {
        path.child(getName());
      }
    }
    moveTo(nodeKey);
    return path;
  }

  @Override
  public String toString() {
    final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);

    if (currentNode instanceof PathNode) {
      final PathNode node = (PathNode) currentNode;
      helper.add("uri", pageReadOnlyTrx.getName(node.getURIKey(), node.getPathKind()));
      helper.add("prefix", pageReadOnlyTrx.getName(node.getPrefixKey(), node.getPathKind()));
      helper.add("localName", pageReadOnlyTrx.getName(node.getLocalNameKey(), node.getPathKind()));
    }

    helper.add("node", currentNode);
    return helper.toString();
  }

  /**
   * Get level of currently selected path node.
   *
   * @return level of currently selected path node
   */
  public int getLevel() {
    assertNotClosed();
    if (currentNode instanceof PathNode) {
      return getPathNode().getLevel();
    }
    return 0;
  }

  @Override
  public boolean hasNode(final @Nonnegative
      long key) {
    assertNotClosed();
    final long currNodeKey = currentNode.getNodeKey();
    final boolean retVal = moveTo(key).hasMoved();
    final boolean movedBack = moveTo(currNodeKey).hasMoved();
    assert movedBack : "moveTo(currNodeKey) must succeed!";
    return retVal;
  }

  @Override
  public boolean hasParent() {
    assertNotClosed();
    return currentNode.hasParent();
  }

  @Override
  public boolean hasFirstChild() {
    assertNotClosed();
    return getStructuralNode().hasFirstChild();
  }

  @Override
  public boolean hasLastChild() {
    assertNotClosed();
    final long nodeKey = currentNode.getNodeKey();
    final boolean retVal = moveToLastChild() == null ? false : true;
    moveTo(nodeKey);
    return retVal;
  }

  @Override
  public boolean hasLeftSibling() {
    assertNotClosed();
    return getStructuralNode().hasLeftSibling();
  }

  @Override
  public boolean hasRightSibling() {
    assertNotClosed();
    return getStructuralNode().hasRightSibling();
  }

  @Override
  public long getNodeKey() {
    assertNotClosed();
    return currentNode.getNodeKey();
  }

  @Override
  public long getLeftSiblingKey() {
    assertNotClosed();
    return getStructuralNode().getLeftSiblingKey();
  }

  @Override
  public long getRightSiblingKey() {
    assertNotClosed();
    return getStructuralNode().getRightSiblingKey();
  }

  @Override
  public long getFirstChildKey() {
    assertNotClosed();
    return getStructuralNode().getFirstChildKey();
  }

  @Override
  public long getLastChildKey() {
    assertNotClosed();
    if (getStructuralNode().hasFirstChild()) {
      moveToFirstChild();
      while (getStructuralNode().hasRightSibling()) {
        moveToRightSibling();
      }
      return currentNode.getNodeKey();
    }
    return Fixed.NULL_NODE_KEY.getStandardProperty();
  }

  @Override
  public long getParentKey() {
    assertNotClosed();
    return currentNode.getParentKey();
  }

  @Override
  public NodeKind getKind() {
    assertNotClosed();
    return currentNode.getKind();
  }

  @Override
  public long getPathNodeKey() {
    assertNotClosed();
    return -1;
  }

  @Override
  public NodeKind getPathKind() {
    assertNotClosed();
    if (currentNode instanceof PathNode) {
      return ((PathNode) currentNode).getPathKind();
    }
    return NodeKind.NULL;
  }

  @Override
  public long getChildCount() {
    assertNotClosed();
    return getStructuralNode().getChildCount();
  }

  @Override
  public long getDescendantCount() {
    assertNotClosed();
    return getStructuralNode().getDescendantCount();
  }

  @Override
  public NodeKind getFirstChildKind() {
    assertNotClosed();
    return NodeKind.PATH;
  }

  @Override
  public NodeKind getLastChildKind() {
    assertNotClosed();
    return NodeKind.PATH;
  }

  @Override
  public NodeKind getLeftSiblingKind() {
    assertNotClosed();
    return NodeKind.PATH;
  }

  @Override
  public NodeKind getParentKind() {
    assertNotClosed();
    if (currentNode.getParentKey() == Fixed.DOCUMENT_NODE_KEY.getStandardProperty()) {
      return NodeKind.XML_DOCUMENT;
    }
    if (currentNode.getParentKey() == Fixed.NULL_NODE_KEY.getStandardProperty()) {
      return NodeKind.UNKNOWN;
    }
    return NodeKind.PATH;
  }

  @Override
  public NodeKind getRightSiblingKind() {
    assertNotClosed();
    return NodeKind.PATH;
  }

  /**
   * Get references.
   *
   * @return number of references of a node
   */
  public int getReferences() {
    assertNotClosed();
    if (currentNode.getKind() == NodeKind.XML_DOCUMENT) {
      return 1;
    } else {
      return getPathNode().getReferences();
    }
  }

  @Override
  public boolean isDocumentRoot() {
    assertNotClosed();
    if (currentNode.getKind() == NodeKind.XML_DOCUMENT) {
      return true;
    }
    return false;
  }

  @Override
  public Move<? extends PathSummaryReader> moveToPrevious() {
    assertNotClosed();
    final StructNode node = getStructuralNode();
    if (node.hasLeftSibling()) {
      // Left sibling node.
      Move<? extends PathSummaryReader> leftSiblMove = moveTo(node.getLeftSiblingKey());
      // Now move down to rightmost descendant node if it has one.
      while (leftSiblMove.trx().hasFirstChild()) {
        leftSiblMove = leftSiblMove.trx().moveToLastChild();
      }
      return leftSiblMove;
    }
    // Parent node.
    return moveTo(node.getParentKey());
  }

  @Override
  public Move<? extends PathSummaryReader> moveToNext() {
    assertNotClosed();
    final StructNode node = getStructuralNode();
    if (node.hasRightSibling()) {
      // Right sibling node.
      return moveTo(node.getRightSiblingKey());
    }
    // Next following node.
    return moveToNextFollowing();
  }

  @Override
  public CommitCredentials getCommitCredentials() {
    assertNotClosed();
    return pageReadOnlyTrx.getCommitCredentials();
  }

  public boolean isNameNode() {
    assertNotClosed();
    if (currentNode instanceof NameNode) {
      return true;
    }
    return false;
  }

  public int getLocalNameKey() {
    assertNotClosed();
    if (currentNode instanceof NameNode) {
      return ((NameNode) currentNode).getLocalNameKey();
    }
    return -1;
  }

  public int getPrefixKey() {
    assertNotClosed();
    if (currentNode instanceof NameNode) {
      return ((NameNode) currentNode).getPrefixKey();
    }
    return -1;
  }

  @Override
  public BigInteger getHash() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getValue() {
    throw new UnsupportedOperationException();
  }
}
