/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.node.xml;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.sirix.api.visitor.VisitResult;
import org.sirix.api.visitor.XmlNodeVisitor;
import org.sirix.node.NodeKind;
import org.sirix.node.SirixDeweyID;
import org.sirix.node.delegates.NodeDelegate;
import org.sirix.node.delegates.StructNodeDelegate;
import org.sirix.node.delegates.ValueNodeDelegate;
import org.sirix.node.immutable.xml.ImmutableText;
import org.sirix.node.interfaces.Node;
import org.sirix.node.interfaces.StructNode;
import org.sirix.node.interfaces.ValueNode;
import org.sirix.node.interfaces.immutable.ImmutableXmlNode;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Node representing a text node.
 */
public final class TextNode extends AbstractStructForwardingNode implements ValueNode, ImmutableXmlNode {

  /** Delegate for common value node information. */
  private final ValueNodeDelegate valueNodeDelegate;

  /** {@link StructNodeDelegate} reference. */
  private final StructNodeDelegate structNodeDelegate;

  /** Value of the node. */
  private byte[] value;

  private BigInteger hash;

  /**
   * Constructor for TextNode.
   *
   * @param valueNodeDelegate delegate for {@link ValueNode} implementation
   * @param structNodeDelegate delegate for {@link StructNode} implementation
   */
  public TextNode(final BigInteger hashCode, final ValueNodeDelegate valueNodeDelegate,
      final StructNodeDelegate structNodeDelegate) {
    hash = hashCode;
    assert structNodeDelegate != null;
    this.structNodeDelegate = structNodeDelegate;
    assert valueNodeDelegate != null;
    this.valueNodeDelegate = valueNodeDelegate;
  }

  /**
   * Constructor for TextNode.
   *
   * @param valueNodeDelegate delegate for {@link ValueNode} implementation
   * @param structNodeDelegate delegate for {@link StructNode} implementation
   */
  public TextNode(final ValueNodeDelegate valueNodeDelegate, final StructNodeDelegate structNodeDelegate) {
    assert structNodeDelegate != null;
    this.structNodeDelegate = structNodeDelegate;
    assert valueNodeDelegate != null;
    this.valueNodeDelegate = valueNodeDelegate;
  }

  @Override
  public BigInteger computeHash() {
    BigInteger result = BigInteger.ONE;

    result = BigInteger.valueOf(31).multiply(result).add(structNodeDelegate.getNodeDelegate().computeHash());
    result = BigInteger.valueOf(31).multiply(result).add(structNodeDelegate.computeHash());
    result = BigInteger.valueOf(31).multiply(result).add(valueNodeDelegate.computeHash());

    return Node.to128BitsAtMaximumBigInteger(result);
  }

  @Override
  public void setHash(final BigInteger hash) {
    this.hash = Node.to128BitsAtMaximumBigInteger(hash);
  }

  @Override
  public BigInteger getHash() {
    return hash;
  }

  @Override
  public NodeKind getKind() {
    return NodeKind.TEXT;
  }

  @Override
  public byte[] getRawValue() {
    if (value == null) {
      value = valueNodeDelegate.getRawValue();
    }
    return value;
  }

  @Override
  public void setValue(final byte[] value) {
    this.value = null;
    valueNodeDelegate.setValue(value);
  }

  @Override
  public long getFirstChildKey() {
    return Fixed.NULL_NODE_KEY.getStandardProperty();
  }

  @Override
  public VisitResult acceptVisitor(final XmlNodeVisitor visitor) {
    return visitor.visit(ImmutableText.of(this));
  }

  @Override
  public void decrementChildCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void incrementChildCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getDescendantCount() {
    return 0;
  }

  @Override
  public void decrementDescendantCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void incrementDescendantCount() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setDescendantCount(long descendantCount) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(structNodeDelegate.getNodeDelegate(), valueNodeDelegate);
  }

  @Override
  public boolean equals(final @Nullable Object obj) {
    if (obj instanceof TextNode) {
      final TextNode other = (TextNode) obj;
      return Objects.equal(structNodeDelegate.getNodeDelegate(), other.getNodeDelegate()) && valueNodeDelegate.equals(
          other.valueNodeDelegate);
    }
    return false;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
                      .add("node delegate", structNodeDelegate.getNodeDelegate())
                      .add("struct delegate", structNodeDelegate)
                      .add("value delegate", valueNodeDelegate)
                      .toString();
  }

  public ValueNodeDelegate getValNodeDelegate() {
    return valueNodeDelegate;
  }

  @Override
  protected NodeDelegate delegate() {
    return structNodeDelegate.getNodeDelegate();
  }

  @Override
  protected StructNodeDelegate structDelegate() {
    return structNodeDelegate;
  }

  @Override
  public String getValue() {
    return new String(valueNodeDelegate.getRawValue(), Constants.DEFAULT_ENCODING);
  }

  @Override
  public Optional<SirixDeweyID> getDeweyID() {
    return structNodeDelegate.getNodeDelegate().getDeweyID();
  }

  @Override
  public int getTypeKey() {
    return structNodeDelegate.getNodeDelegate().getTypeKey();
  }
}
