package strawman

package collection.immutable

import strawman.collection.{IterableFactory, Iterator}
import strawman.collection.mutable.{Builder, ImmutableSetBuilder}

import scala.{Any, AnyRef, Array, Boolean, inline, Int, NoSuchElementException, Serializable, SerialVersionUID, sys, Unit}
import scala.Predef.assert
import java.lang.Integer

/** This class implements immutable sets using a hash trie.
  *
  *  '''Note:''' The builder of this hash set may return specialized representations for small sets.
  *
  *  @tparam A      the type of the elements contained in this hash set.
  *
  *  @author  Martin Odersky
  *  @author  Tiark Rompf
  *  @version 2.8
  *  @since   2.3
  *  @define Coll `immutable.HashSet2`
  *  @define coll immutable hash set
  */
@SerialVersionUID(2L)
sealed trait HashSet2[A]
  extends Set[A]
    with SetLike[A, HashSet2]
    with Serializable {

  import HashSet2.nullToEmpty

  // From IterablePolyTransforms
  def fromIterable[B](coll: collection.Iterable[B]): HashSet2[B] = HashSet2.fromIterable(coll)
  protected[this] def fromIterableWithSameElemType(coll: collection.Iterable[A]): HashSet2[A] = fromIterable(coll)

  // From SetLike
  def contains(elem: A): Boolean = get0(elem, computeHash(elem), 0)

  def + (elem: A): HashSet2[A] = updated0(elem, computeHash(elem), 0)

  def - (elem: A): HashSet2[A] = nullToEmpty(removed0(elem, computeHash(elem), 0))

  protected def get0(key: A, hash: Int, level: Int): Boolean

  protected def updated0(key: A, hash: Int, level: Int): HashSet2[A]

  protected def removed0(key: A, hash: Int, level: Int): HashSet2[A]

  protected def computeHash(key: A): Int = improve(elemHashCode(key))

  protected final def improve(hcode: Int): Int = {
    var h: Int = hcode + ~(hcode << 9)
    h = h ^ (h >>> 14)
    h = h + (h << 4)
    h ^ (h >>> 10)
  }

  protected final def elemHashCode(elem: A): Int = elem.##

  override def ++ (that: collection.Set[A]): HashSet2[A] =
    if (that.isEmpty) this
    else {
      var result = this
      val it = that.iterator()
      while (it.hasNext) {
        result = result + it.next()
      }
      result
    }

}

object HashSet2 extends IterableFactory[HashSet2] {

  def fromIterable[A](it: collection.Iterable[A]): HashSet2[A] =
    it match {
      case h: HashSet2[A] => h
      case _ => newBuilder[A].++=(it).result
    }

  def newBuilder[A]: Builder[A, HashSet2[A]] = new ImmutableSetBuilder[A, HashSet2](empty[A])

  override def empty[A <: Any]: HashSet2[A] = EmptyHashSet.asInstanceOf[HashSet2[A]]

  private object EmptyHashSet extends HashSet2[Any] {

    def iterator(): Iterator[Any] = Iterator.empty

    override def foreach[U](f: Any => U): Unit = ()

    override def head: Any = throw new NoSuchElementException("Empty Set")

    override def tail: HashSet2[Any] = throw new NoSuchElementException("Empty Set")

    override def size: Int = 0

    protected def get0(key: Any, hash: Int, level: Int): Boolean = false

    protected def updated0(key: Any, hash: Int, level: Int): HashSet2[Any] = new HashSet2.HashSet1(key, hash)

    protected def removed0(key: Any, hash: Int, level: Int): HashSet2[Any] = this

  }

  /**
    * Common superclass of HashSet1 and HashSetCollision1, which are the two possible leaves of the Trie
    */
  private[HashSet2] sealed abstract class LeafHashSet[A] extends HashSet2[A] {
    private[HashSet2] def hash:Int
  }

  private[immutable] final class HashSet1[A](private[HashSet2] val key: A, private[HashSet2] val hash: Int) extends LeafHashSet[A] {

    def iterator(): Iterator[A] = Iterator.single(key)

    override def size: Int = 1

    protected def get0(key: A, hash: Int, level: Int) =
      (hash == this.hash && key == this.key)

    protected def updated0(key: A, hash: Int, level: Int) =
      if (hash == this.hash && key == this.key) this
      else {
        if (hash != this.hash) {
          makeHashTrieSet(this.hash, this, hash, new HashSet1(key, hash), level)
        } else {
          // 32-bit hash collision (rare, but not impossible)
          new HashSetCollision1(hash, ListSet.empty + this.key + key)
        }
      }

    protected def removed0(key: A, hash: Int, level: Int) =
      if (hash == this.hash && key == this.key) null else this

  }

  private[immutable] final class HashSetCollision1[A](private[HashSet2] val hash: Int, val ks: ListSet[A]) extends LeafHashSet[A] {

    override def size = ks.size

    protected def get0(key: A, hash: Int, level: Int) =
      if (hash == this.hash) ks.contains(key) else false

    protected def updated0(key: A, hash: Int, level: Int): HashSet2[A] =
      if (hash == this.hash) new HashSetCollision1(hash, ks + key)
      else makeHashTrieSet(this.hash, this, hash, new HashSet1(key, hash), level)

    protected def removed0(key: A, hash: Int, level: Int): HashSet2[A] =
      if (hash == this.hash) {
        val ks1 = ks - key
        ks1.size match {
          case 0 =>
            // the empty set
            null
          case 1 =>
            // create a new HashSet1 with the hash we already know
            new HashSet1(ks1.head, hash)
          case size if size == ks.size =>
            // Should only have HSC1 if size > 1
            this
          case _ =>
            // create a new HashSetCollision with the hash we already know and the new keys
            new HashSetCollision1(hash, ks1)
        }
      } else this

    def iterator: Iterator[A] = ks.iterator
    override def foreach[U](f: A => U): Unit = ks.foreach(f)

    private def writeObject(out: java.io.ObjectOutputStream) {
      // this cannot work - reading things in might produce different
      // hash codes and remove the collision. however this is never called
      // because no references to this class are ever handed out to client code
      // and HashTrieSet serialization takes care of the situation
      sys.error("cannot serialize an immutable.HashSet2 where all items have the same 32-bit hash code")
      //out.writeObject(kvs)
    }

    private def readObject(in: java.io.ObjectInputStream) {
      sys.error("cannot deserialize an immutable.HashSet2 where all items have the same 32-bit hash code")
      //kvs = in.readObject().asInstanceOf[ListSet[A]]
      //hash = computeHash(kvs.)
    }

  }


  /**
    * A branch node of the HashTrieSet with at least one and up to 32 children.
    *
    * @param bitmap encodes which element corresponds to which child
    * @param elems the up to 32 children of this node.
    *              the number of children must be identical to the number of 1 bits in bitmap
    * @param size0 the total number of elements. This is stored just for performance reasons.
    * @tparam A      the type of the elements contained in this hash set.
    *
    * How levels work:
    *
    * When looking up or adding elements, the part of the hashcode that is used to address the children array depends
    * on how deep we are in the tree. This is accomplished by having a level parameter in all internal methods
    * that starts at 0 and increases by 5 (32 = 2^5) every time we go deeper into the tree.
    *
    * hashcode (binary): 00000000000000000000000000000000
    * level=0 (depth=0)                             ^^^^^
    * level=5 (depth=1)                        ^^^^^
    * level=10 (depth=2)                  ^^^^^
    * ...
    *
    * Be careful: a non-toplevel HashTrieSet is not a self-contained set, so e.g. calling contains on it will not work!
    * It relies on its depth in the Trie for which part of a hash to use to address the children, but this information
    * (the level) is not stored due to storage efficiency reasons but has to be passed explicitly!
    *
    * How bitmap and elems correspond:
    *
    * A naive implementation of a HashTrieSet would always have an array of size 32 for children and leave the unused
    * children empty (null). But that would be very wasteful regarding memory. Instead, only non-empty children are
    * stored in elems, and the bitmap is used to encode which elem corresponds to which child bucket. The lowest 1 bit
    * corresponds to the first element, the second-lowest to the second, etc.
    *
    * bitmap (binary): 00010000000000000000100000000000
    * elems: [a,b]
    * children:        ---b----------------a-----------
    */
  private[immutable] final class HashTrieSet[A](private val bitmap: Int, private[collection] val elems: Array[HashSet2[A]], private val size0: Int)
    extends HashSet2[A] {
    assert(Integer.bitCount(bitmap) == elems.length)
    // assertion has to remain disabled until SI-6197 is solved
    // assert(elems.length > 1 || (elems.length == 1 && elems(0).isInstanceOf[HashTrieSet[_]]))

    def iterator(): Iterator[A] = new TrieIterator[A](elems.asInstanceOf[Array[Iterable[A]]]) {
      final override def getElem(cc: AnyRef): A = cc.asInstanceOf[HashSet1[A]].key
    }

    override def size = size0

    protected def get0(key: A, hash: Int, level: Int) = {
      val index = (hash >>> level) & 0x1f
      val mask = (1 << index)
      if (bitmap == - 1) {
        elems(index & 0x1f).get0(key, hash, level + 5)
      } else if ((bitmap & mask) != 0) {
        val offset = Integer.bitCount(bitmap & (mask-1))
        elems(offset).get0(key, hash, level + 5)
      } else
        false
    }

    protected def updated0(key: A, hash: Int, level: Int) = {
      val index = (hash >>> level) & 0x1f
      val mask = (1 << index)
      val offset = Integer.bitCount(bitmap & (mask-1))
      if ((bitmap & mask) != 0) {
        val sub = elems(offset)
        val subNew = sub.updated0(key, hash, level + 5)
        if (sub eq subNew) this
        else {
          val elemsNew = new Array[HashSet2[A]](elems.length)
          Array.copy(elems, 0, elemsNew, 0, elems.length)
          elemsNew(offset) = subNew
          new HashTrieSet(bitmap, elemsNew, size + (subNew.size - sub.size))
        }
      } else {
        val elemsNew = new Array[HashSet2[A]](elems.length + 1)
        Array.copy(elems, 0, elemsNew, 0, offset)
        elemsNew(offset) = new HashSet1(key, hash)
        Array.copy(elems, offset, elemsNew, offset + 1, elems.length - offset)
        val bitmapNew = bitmap | mask
        new HashTrieSet(bitmapNew, elemsNew, size + 1)
      }
    }

    protected def removed0(key: A, hash: Int, level: Int): HashSet2[A] = {
      val index = (hash >>> level) & 0x1f
      val mask = (1 << index)
      val offset = Integer.bitCount(bitmap & (mask-1))
      if ((bitmap & mask) != 0) {
        val sub = elems(offset)
        val subNew = sub.removed0(key, hash, level + 5)
        if (sub eq subNew) this
        else if (subNew eq null) {
          val bitmapNew = bitmap ^ mask
          if (bitmapNew != 0) {
            val elemsNew = new Array[HashSet2[A]](elems.length - 1)
            Array.copy(elems, 0, elemsNew, 0, offset)
            Array.copy(elems, offset + 1, elemsNew, offset, elems.length - offset - 1)
            val sizeNew = size - sub.size
            // if we have only one child, which is not a HashTrieSet but a self-contained set like
            // HashSet1 or HashSetCollision1, return the child instead
            if (elemsNew.length == 1 && !elemsNew(0).isInstanceOf[HashTrieSet[_]])
              elemsNew(0)
            else
              new HashTrieSet(bitmapNew, elemsNew, sizeNew)
          } else
            null
        } else if(elems.length == 1 && !subNew.isInstanceOf[HashTrieSet[_]]) {
          subNew
        } else {
          val elemsNew = new Array[HashSet2[A]](elems.length)
          Array.copy(elems, 0, elemsNew, 0, elems.length)
          elemsNew(offset) = subNew
          val sizeNew = size + (subNew.size - sub.size)
          new HashTrieSet(bitmap, elemsNew, sizeNew)
        }
      } else {
        this
      }
    }
  }

  // utility method to create a HashTrieSet from two leaf HashSets (HashSet1 or HashSetCollision1) with non-colliding hash code)
  private def makeHashTrieSet[A](hash0:Int, elem0:HashSet2[A], hash1:Int, elem1:HashSet2[A], level:Int) : HashTrieSet[A] = {
    val index0 = (hash0 >>> level) & 0x1f
    val index1 = (hash1 >>> level) & 0x1f
    if(index0 != index1) {
      val bitmap = (1 << index0) | (1 << index1)
      val elems = new Array[HashSet2[A]](2)
      if(index0 < index1) {
        elems(0) = elem0
        elems(1) = elem1
      } else {
        elems(0) = elem1
        elems(1) = elem0
      }
      new HashTrieSet[A](bitmap, elems, elem0.size + elem1.size)
    } else {
      val elems = new Array[HashSet2[A]](1)
      val bitmap = (1 << index0)
      val child = makeHashTrieSet(hash0, elem0, hash1, elem1, level + 5)
      elems(0) = child
      new HashTrieSet[A](bitmap, elems, child.size)
    }
  }

  /**
    * In many internal operations the empty set is represented as null for performance reasons. This method converts
    * null to the empty set for use in public methods
    */
  @inline private def nullToEmpty[A](s: HashSet2[A]): HashSet2[A] = if (s eq null) empty[A] else s

}