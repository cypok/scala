 /* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.reflect
package internal

import scala.collection.{ mutable, immutable }
import scala.collection.mutable.ListBuffer
import util.Statistics._
import Flags._
import api.Modifier

trait Symbols extends api.Symbols { self: SymbolTable =>
  import definitions._

  protected var ids = 0

  val emptySymbolArray = new Array[Symbol](0)

  def symbolCount = ids // statistics

  protected def nextId() = { ids += 1; ids }

  /** Used for deciding in the IDE whether we can interrupt the compiler */
  //protected var activeLocks = 0

  /** Used for debugging only */
  //protected var lockedSyms = collection.immutable.Set[Symbol]()

  /** Used to keep track of the recursion depth on locked symbols */
  private var recursionTable = immutable.Map.empty[Symbol, Int]

  private var nextexid = 0
  protected def freshExistentialName(suffix: String) = {
    nextexid += 1
    newTypeName("_" + nextexid + suffix)
  }

  // Set the fields which point companions at one another.  Returns the module.
  def connectModuleToClass(m: ModuleSymbol, moduleClass: ClassSymbol): ModuleSymbol = {
    moduleClass.sourceModule = m
    m setModuleClass moduleClass
    m
  }

  /** Create a new free variable.  Its owner is NoSymbol.
   */
  def newFreeVar(name: TermName, tpe: Type, value: Any, newFlags: Long = 0L): FreeVar =
    new FreeVar(name, value) initFlags newFlags setInfo tpe

  /** The original owner of a class. Used by the backend to generate
   *  EnclosingMethod attributes.
   */
  val originalOwner = perRunCaches.newMap[Symbol, Symbol]()

  abstract class AbsSymbolImpl extends AbsSymbol {
    this: Symbol =>

    def newNestedSymbol(name: Name, pos: Position, newFlags: Long, isClass: Boolean): Symbol = name match {
      case n: TermName => newTermSymbol(n, pos, newFlags)
      case n: TypeName => if (isClass) newClassSymbol(n, pos, newFlags) else newNonClassSymbol(n, pos, newFlags)
    }

    def enclosingClass: Symbol            = enclClass
    def enclosingMethod: Symbol           = enclMethod
    def thisPrefix: Type                  = thisType
    def selfType: Type                    = typeOfThis
    def typeSignature: Type               = info
    def typeSignatureIn(site: Type): Type = site memberInfo this

    def asType: Type = tpe
    def asTypeIn(site: Type): Type = site.memberType(this)
    def asTypeConstructor: Type = typeConstructor
    def setInternalFlags(flag: Long): this.type = { setFlag(flag); this }
    def setTypeSignature(tpe: Type): this.type = { setInfo(tpe); this }
    def setAnnotations(annots: AnnotationInfo*): this.type = { setAnnotations(annots.toList); this }
  }

  /** The class for all symbols */
  abstract class Symbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: Name)
          extends AbsSymbolImpl
             with HasFlags
             with SymbolFlagLogic
             with SymbolCreator
             // with FlagVerifier   // DEBUG
             with Annotatable[Symbol] {

    type AccessBoundaryType = Symbol
    type AnnotationType     = AnnotationInfo

    private[this] var _rawowner = initOwner // Syncnote: need not be protected, as only assignment happens in owner_=, which is not exposed to api
    private[this] var _rawname  = initName
    private[this] var _rawflags: Long = _

    def rawowner = _rawowner
    def rawname  = _rawname
    def rawflags = _rawflags

    private var rawpos = initPos

    val id = nextId() // identity displayed when -uniqid
    private[this] var _validTo: Period = NoPeriod

    if (traceSymbolActivity)
      traceSymbols.recordNewSymbol(this)

    def validTo = _validTo
    def validTo_=(x: Period) { _validTo = x}

    def pos = rawpos
    def setPos(pos: Position): this.type = { this.rawpos = pos; this }

    /** !!! The logic after "hasFlag" is far too opaque to be unexplained.
     *  I'm guessing it's attempting to compensate for flag overloading,
     *  and embedding such logic in an undocumented island like this is a
     *  notarized guarantee of future breakage.
     */
    override def hasModifier(mod: Modifier) =
      hasFlag(flagOfModifier(mod)) &&
      (!(mod == Modifier.bynameParameter) || isTerm) &&
      (!(mod == Modifier.covariant) || isType)

    override def modifiers: Set[Modifier] =
      Modifier.values filter hasModifier

// ------ creators -------------------------------------------------------------------

    final def newValue(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): TermSymbol =
      newTermSymbol(name, pos, newFlags)
    final def newVariable(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): TermSymbol =
      newTermSymbol(name, pos, MUTABLE | newFlags)
    final def newValueParameter(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): TermSymbol =
      newTermSymbol(name, pos, PARAM | newFlags)

    /** Create local dummy for template (owner of local blocks) */
    final def newLocalDummy(pos: Position) =
      newTermSymbol(nme.localDummyName(this), pos) setInfo NoType
    final def newMethod(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): MethodSymbol =
      createMethodSymbol(name, pos, METHOD | newFlags)
    final def newLabel(name: TermName, pos: Position = NoPosition): MethodSymbol =
      newMethod(name, pos, LABEL)

    /** Propagates ConstrFlags (JAVA, specifically) from owner to constructor. */
    final def newConstructor(pos: Position, newFlags: Long = 0L) =
      newMethod(nme.CONSTRUCTOR, pos, getFlag(ConstrFlags) | newFlags)

    /** Static constructor with info set. */
    def newStaticConstructor(pos: Position) =
      newConstructor(pos, STATIC) setInfo UnitClass.tpe

    /** Instance constructor with info set. */
    def newClassConstructor(pos: Position) =
      newConstructor(pos) setInfo MethodType(Nil, this.tpe)

    def newLinkedModule(clazz: Symbol, newFlags: Long = 0L): ModuleSymbol = {
      val m = newModuleSymbol(clazz.name.toTermName, clazz.pos, MODULE | newFlags)
      connectModuleToClass(m, clazz.asInstanceOf[ClassSymbol])
    }
    final def newModule(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): ModuleSymbol = {
      val m     = newModuleSymbol(name, pos, newFlags | MODULE)
      val clazz = newModuleClass(name.toTypeName, pos, m getFlag ModuleToClassFlags)
      connectModuleToClass(m, clazz)
    }

    final def newPackage(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): ModuleSymbol = {
      assert(name == nme.ROOT || isPackageClass, this)
      newModule(name, pos, PackageFlags | newFlags)
    }

    final def newThisSym(pos: Position) =
      newTermSymbol(nme.this_, pos, SYNTHETIC)
    final def newImport(pos: Position) =
      newTermSymbol(nme.IMPORT, pos)

    final def newModuleSymbol(name: TermName, pos: Position = NoPosition, newFlags: Long = 0L): ModuleSymbol =
      newTermSymbol(name, pos, newFlags).asInstanceOf[ModuleSymbol]

    final def newModuleClassSymbol(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L): ModuleClassSymbol =
      newClassSymbol(name, pos, newFlags).asInstanceOf[ModuleClassSymbol]

    final def newTypeSkolemSymbol(name: TypeName, origin: AnyRef, pos: Position = NoPosition, newFlags: Long = 0L): TypeSkolem =
      createTypeSkolemSymbol(name, origin, pos, newFlags)

    /** @param pre   type relative to which alternatives are seen.
     *  for instance:
     *  class C[T] {
     *    def m(x: T): T
     *    def m'(): T
     *  }
     *  val v: C[Int]
     *
     *  Then v.m  has symbol TermSymbol(flags = {OVERLOADED},
     *                                  tpe = OverloadedType(C[Int], List(m, m')))
     *  You recover the type of m doing a
     *
     *    m.tpe.asSeenFrom(pre, C)   (generally, owner of m, which is C here).
     *
     *  or:
     *
     *    pre.memberType(m)
     */
    final def newOverloaded(pre: Type, alternatives: List[Symbol]): Symbol = (
      newTermSymbol(alternatives.head.name.toTermName, alternatives.head.pos, OVERLOADED)
        setInfo OverloadedType(pre, alternatives)
    )

    final def newErrorValue(name: TermName) =
      newTermSymbol(name, pos, SYNTHETIC | IS_ERROR) setInfo ErrorType

    /** Symbol of a type definition  type T = ...
     */
    final def newAliasType(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L): Symbol =
      createAliasTypeSymbol(name, pos, newFlags)

    /** Symbol of an abstract type  type T >: ... <: ...
     */
    final def newAbstractType(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L): Symbol =
      createAbstractTypeSymbol(name, pos, DEFERRED | newFlags)

    /** Symbol of a type parameter
     */
    final def newTypeParameter(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L) =
      newAbstractType(name, pos, PARAM | newFlags)

    /** Symbol of an existential type T forSome { ... }
     */
    final def newExistential(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L): Symbol =
      newAbstractType(name, pos, EXISTENTIAL | newFlags)

    /** Synthetic value parameters when parameter symbols are not available
     */
    final def newSyntheticValueParamss(argtypess: List[List[Type]]): List[List[Symbol]] = {
      var cnt = 0
      def freshName() = { cnt += 1; nme.syntheticParamName(cnt) }
      mmap(argtypess)(tp => newValueParameter(freshName(), focusPos(owner.pos), SYNTHETIC) setInfo tp)
    }

    def newSyntheticTypeParam(): Symbol                             = newSyntheticTypeParam("T0", 0L)
    def newSyntheticTypeParam(name: String, newFlags: Long): Symbol = newTypeParameter(newTypeName(name), NoPosition, newFlags) setInfo TypeBounds.empty
    def newSyntheticTypeParams(num: Int): List[Symbol]              = (0 until num).toList map (n => newSyntheticTypeParam("T" + n, 0L))

    /** Create a new existential type skolem with this symbol its owner,
     *  based on the given symbol and origin.
     */
    def newExistentialSkolem(basis: Symbol, origin: AnyRef): TypeSkolem = {
      val skolem = newTypeSkolemSymbol(basis.name.toTypeName, origin, basis.pos, (basis.flags | EXISTENTIAL) & ~PARAM)
      skolem setInfo (basis.info cloneInfo skolem)
    }

    // flags set up to maintain TypeSkolem's invariant: origin.isInstanceOf[Symbol] == !hasFlag(EXISTENTIAL)
    // CASEACCESSOR | SYNTHETIC used to single this symbol out in deskolemizeGADT
    def newGADTSkolem(name: TypeName, origin: Symbol, info: Type): TypeSkolem =
      newTypeSkolemSymbol(name, origin, origin.pos, origin.flags & ~(EXISTENTIAL | PARAM) | CASEACCESSOR | SYNTHETIC) setInfo info

    final def freshExistential(suffix: String): Symbol =
      newExistential(freshExistentialName(suffix), pos)

    /** Synthetic value parameters when parameter symbols are not available.
     *  Calling this method multiple times will re-use the same parameter names.
     */
    final def newSyntheticValueParams(argtypes: List[Type]): List[Symbol] =
      newSyntheticValueParamss(List(argtypes)).head

    /** Synthetic value parameter when parameter symbol is not available.
     *  Calling this method multiple times will re-use the same parameter name.
     */
    final def newSyntheticValueParam(argtype: Type): Symbol =
      newSyntheticValueParams(List(argtype)).head

    /** Type skolems are type parameters ''seen from the inside''
     *  Assuming a polymorphic method m[T], its type is a PolyType which has a TypeParameter
     *  with name `T` in its typeParams list. While type checking the parameters, result type and
     *  body of the method, there's a local copy of `T` which is a TypeSkolem.
     */
    final def newTypeSkolem: Symbol =
      owner.newTypeSkolemSymbol(name.toTypeName, this, pos, flags)

    final def newClass(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L) =
      newClassSymbol(name, pos, newFlags)

    /** A new class with its info set to a ClassInfoType with given scope and parents. */
    def newClassWithInfo(name: TypeName, parents: List[Type], scope: Scope, pos: Position = NoPosition, newFlags: Long = 0L) = {
      val clazz = newClass(name, pos, newFlags)
      clazz setInfo ClassInfoType(parents, scope, clazz)
    }
    final def newErrorClass(name: TypeName) =
      newClassWithInfo(name, Nil, new ErrorScope(this), pos, SYNTHETIC | IS_ERROR)

    final def newModuleClass(name: TypeName, pos: Position = NoPosition, newFlags: Long = 0L) =
      newModuleClassSymbol(name, pos, newFlags | MODULE)

    final def newAnonymousClass(pos: Position) =
      newClassSymbol(tpnme.ANON_CLASS_NAME, pos)

    final def newAnonymousFunctionClass(pos: Position, newFlags: Long = 0L) =
      newClassSymbol(tpnme.ANON_FUN_NAME, pos, FINAL | SYNTHETIC | newFlags)

    final def newAnonymousFunctionValue(pos: Position, newFlags: Long = 0L) =
      newTermSymbol(nme.ANON_FUN_NAME, pos, SYNTHETIC | newFlags) setInfo NoType

    /** Refinement types P { val x: String; type T <: Number }
     *  also have symbols, they are refinementClasses
     */
    final def newRefinementClass(pos: Position) = createRefinementClassSymbol(pos, 0L)

    /** Create a new getter for current symbol (which must be a field)
     */
    final def newGetter: Symbol = (
      owner.newMethod(nme.getterName(name.toTermName), NoPosition, getterFlags(flags))
        setPrivateWithin privateWithin
        setInfo MethodType(Nil, tpe)
    )

    final def newErrorSymbol(name: Name): Symbol = name match {
      case x: TypeName  => newErrorClass(x)
      case x: TermName  => newErrorValue(x)
    }

    @deprecated("Use the other signature", "2.10.0")
    def newClass(pos: Position, name: TypeName): Symbol        = newClass(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newModuleClass(pos: Position, name: TypeName): Symbol  = newModuleClass(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newLabel(pos: Position, name: TermName): MethodSymbol  = newLabel(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newValue(pos: Position, name: TermName): TermSymbol    = newTermSymbol(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newAliasType(pos: Position, name: TypeName): Symbol    = newAliasType(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newAbstractType(pos: Position, name: TypeName): Symbol = newAbstractType(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newExistential(pos: Position, name: TypeName): Symbol  = newExistential(name, pos)
    @deprecated("Use the other signature", "2.10.0")
    def newMethod(pos: Position, name: TermName): MethodSymbol = newMethod(name, pos)

// ----- locking and unlocking ------------------------------------------------------

    // True if the symbol is unlocked.
    // True if the symbol is locked but still below the allowed recursion depth.
    // False otherwise
    private[scala] def lockOK: Boolean = {
      ((_rawflags & LOCKED) == 0L) ||
      ((settings.Yrecursion.value != 0) &&
       (recursionTable get this match {
         case Some(n) => (n <= settings.Yrecursion.value)
         case None => true }))
    }

    // Lock a symbol, using the handler if the recursion depth becomes too great.
    private[scala] def lock(handler: => Unit): Boolean = {
      if ((_rawflags & LOCKED) != 0L) {
        if (settings.Yrecursion.value != 0) {
          recursionTable get this match {
            case Some(n) =>
              if (n > settings.Yrecursion.value) {
                handler
                false
              } else {
                recursionTable += (this -> (n + 1))
                true
              }
            case None =>
              recursionTable += (this -> 1)
              true
          }
        } else { handler; false }
      } else {
        _rawflags |= LOCKED
        true
//        activeLocks += 1
//        lockedSyms += this
      }
    }

    // Unlock a symbol
    private[scala] def unlock() = {
      if ((_rawflags & LOCKED) != 0L) {
//        activeLocks -= 1
//        lockedSyms -= this
        _rawflags &= ~LOCKED
        if (settings.Yrecursion.value != 0)
          recursionTable -= this
      }
    }

// ----- tests ----------------------------------------------------------------------

    /** All symbols are one of three categories: TermSymbol, TypeSymbol, or NoSymbol.
     *  There is only one NoSymbol.
     */
    def isTerm = false
    def isType = false

    /** TypeSymbols fall into four named direct subclasses:
     *   - ClassSymbol
     *   - AliasTypeSymbol
     *   - AbstractTypeSymbol
     *   - TypeSkolem
     */
    def isClass        = false
    def isAliasType    = false
    def isAbstractType = false
    def isSkolem       = false

    /** A Type, but not a Class. */
    def isNonClassType = false

    /** The bottom classes are Nothing and Null, found in Definitions. */
    def isBottomClass  = false

    /** These are all tests for varieties of ClassSymbol, which has these subclasses:
     *  - ModuleClassSymbol
     *  - RefinementClassSymbol
     *  - PackageClassSymbol (extends ModuleClassSymbol)
     */
    def isAbstractClass         = false
    def isAnonOrRefinementClass = false
    def isAnonymousClass        = false
    def isConcreteClass         = false
    def isImplClass             = false   // the implementation class of a trait
    def isModuleClass           = false
    def isNumericValueClass     = false
    def isPrimitiveValueClass   = false
    def isRefinementClass       = false
    override def isTrait = false

    /** Qualities of Types, always false for TermSymbols.
     */
    def isContravariant         = false
    def isCovariant             = false
    def isExistentialSkolem     = false
    def isExistentiallyBound    = false
    def isExistentialQuantified = false
    def isGADTSkolem            = false
    def isTypeParameter         = false
    def isTypeParameterOrSkolem = false
    def isTypeSkolem            = false

    /** Qualities of Terms, always false for TypeSymbols.
     */
    override def hasDefault  = false
    def isBridge             = false
    def isEarlyInitialized   = false
    def isModule             = false
    def isOverloaded         = false
    def isValueParameter     = false

    /** Qualities of MethodSymbols, always false for TypeSymbols
     *  and other TermSymbols.
     */
    def isCaseAccessorMethod = false
    def isLiftedMethod       = false
    def isMacro              = false
    def isMethod             = false
    def isSourceMethod       = false
    def isVarargsMethod      = false

    /** Package/package object tests */
    def isPackage              = false
    def isPackageClass         = false
    def isPackageObject        = false
    def isPackageObjectClass   = false

    def isPackageObjectOrClass = isPackageObject || isPackageObjectClass
    def isModuleOrModuleClass  = isModule || isModuleClass

    /** Overridden in custom objects in Definitions */
    def isRoot              = false
    def isRootPackage       = false
    def isRootSymbol        = false   // RootPackage and RootClass.  TODO: also NoSymbol.
    def isEmptyPackage      = false
    def isEmptyPackageClass = false

    /** Is this symbol an effective root for fullname string?
     */
    def isEffectiveRoot = false

    /** For RootClass, this is EmptyPackageClass.  For all other symbols,
     *  the symbol itself.
     */
    def ownerOfNewSymbols = this

    final def isLazyAccessor       = isLazy && lazyAccessor != NoSymbol
    final def isOverridableMember  = !(isClass || isEffectivelyFinal) && owner.isClass

    /** Does this symbol denote a wrapper created by the repl? */
    final def isInterpreterWrapper = (
         (this hasFlag MODULE)
      && owner.isPackageClass
      && nme.isReplWrapperName(name)
    )
    @inline final override def getFlag(mask: Long): Long = flags & mask
    /** Does symbol have ANY flag in `mask` set? */
    @inline final override def hasFlag(mask: Long): Boolean = (flags & mask) != 0
    /** Does symbol have ALL the flags in `mask` set? */
    @inline final override def hasAllFlags(mask: Long): Boolean = (flags & mask) == mask
    
    override def setFlag(mask: Long): this.type   = { _rawflags |= mask ; this }
    override def resetFlag(mask: Long): this.type = { _rawflags &= ~mask ; this }
    override def resetFlags() { rawflags &= (TopLevelCreationFlags | alwaysHasFlags) }
    
    /** Default implementation calls the generic string function, which
     *  will print overloaded flags as <flag1/flag2/flag3>.  Subclasses
     *  of Symbol refine.
     */
    override def resolveOverloadedFlag(flag: Long): String = Flags.flagToString(flag)

    /** Set the symbol's flags to the given value, asserting
     *  that the previous value was 0.
     */
    override def initFlags(mask: Long): this.type = {
      assert(rawflags == 0L, symbolCreationString)
      _rawflags = mask
      this
    }
    
    final def flags: Long = {
      val fs = _rawflags & phase.flagMask
      (fs | ((fs & LateFlags) >>> LateShift)) & ~(fs >>> AntiShift)
    }
    def flags_=(fs: Long) = _rawflags = fs
    def rawflags_=(x: Long) { _rawflags = x }

    /** Term symbols with the exception of static parts of Java classes and packages.
     */
    final def isValue    = isTerm && !(isModule && hasFlag(PACKAGE | JAVA))
    final def isVariable = isTerm && isMutable && !isMethod

    // interesting only for lambda lift. Captured variables are accessed from inner lambdas.
    final def isCapturedVariable  = isVariable && hasFlag(CAPTURED)

    final def isGetter = isTerm && hasAccessorFlag && !nme.isSetterName(name)
    // todo: make independent of name, as this can be forged.
    final def isSetter = isTerm && hasAccessorFlag && nme.isSetterName(name)
    def isSetterParameter = isValueParameter && owner.isSetter

    final def hasGetter = isTerm && nme.isLocalName(name)

    final def isInitializedToDefault = !isType && hasAllFlags(DEFAULTINIT | ACCESSOR)
    final def isLocalDummy = isTerm && nme.isLocalDummyName(name)
    final def isClassConstructor = isTerm && (name == nme.CONSTRUCTOR)
    final def isMixinConstructor = isTerm && (name == nme.MIXIN_CONSTRUCTOR)
    final def isConstructor = isTerm && nme.isConstructorName(name)
    final def isStaticModule = isModule && isStatic && !isMethod
    final def isThisSym = isTerm && owner.thisSym == this
    final def isError = hasFlag(IS_ERROR)
    final def isErroneous = isError || isInitialized && tpe.isErroneous

    def isHigherOrderTypeParameter = owner.isTypeParameterOrSkolem

    // class C extends D( { class E { ... } ... } ). Here, E is a class local to a constructor
    def isClassLocalToConstructor = false

    final def isDerivedValueClass =
      isClass && info.firstParent.typeSymbol == AnyValClass && !isPrimitiveValueClass

    final def isMethodWithExtension =
      isMethod && owner.isDerivedValueClass && !isParamAccessor && !isConstructor && !hasFlag(SUPERACCESSOR)

    final def isAnonymousFunction = isSynthetic && (name containsName tpnme.ANON_FUN_NAME)
    final def isDefinedInPackage  = effectiveOwner.isPackageClass
    final def isJavaInterface     = isJavaDefined && isTrait
    final def needsFlatClasses    = phase.flatClasses && rawowner != NoSymbol && !rawowner.isPackageClass

    /** change name by appending $$<fully-qualified-name-of-class `base`>
     *  Do the same for any accessed symbols or setters/getters.
     *  Implementation in TermSymbol.
     */
    def expandName(base: Symbol) { }

    // In java.lang, Predef, or scala package/package object
    def isInDefaultNamespace = UnqualifiedOwners(effectiveOwner)

    /** The owner, skipping package objects.
     */
    def effectiveOwner = owner.skipPackageObject

    /** If this is a package object or its implementing class, its owner: otherwise this.
     */
    def skipPackageObject: Symbol = if (this.isPackageObjectOrClass) owner else this

    /** If this is a constructor, its owner: otherwise this.
     */
    final def skipConstructor: Symbol = if (isConstructor) owner else this

    /** Conditions where we omit the prefix when printing a symbol, to avoid
     *  unpleasantries like Predef.String, $iw.$iw.Foo and <empty>.Bippy.
     */
    final def isOmittablePrefix = /*!settings.debug.value &&*/ (
         UnqualifiedOwners(skipPackageObject)
      || isEmptyPrefix
    )
    def isEmptyPrefix = (
         isEffectiveRoot                      // has no prefix for real, <empty> or <root>
      || isAnonOrRefinementClass              // has uninteresting <anon> or <refinement> prefix
      || nme.isReplWrapperName(name)          // has ugly $iw. prefix (doesn't call isInterpreterWrapper due to nesting)
    )
    def isFBounded = info.baseTypeSeq exists (_ contains this)

    /** Is symbol a monomorphic type?
     *  assumption: if a type starts out as monomorphic, it will not acquire
     *  type parameters in later phases.
     */
    final def isMonomorphicType =
      isType && {
        var is = infos
        (is eq null) || {
          while (is.prev ne null) { is = is.prev }
          is.info.isComplete && !is.info.isHigherKinded // was: is.info.typeParams.isEmpty.
          // YourKit listed the call to PolyType.typeParams as a hot spot but it is likely an artefact.
          // The change to isHigherKinded did not reduce the total running time.
        }
      }

    def isStrictFP          = hasAnnotation(ScalaStrictFPAttr) || (enclClass hasAnnotation ScalaStrictFPAttr)
    def isSerializable      = (
         info.baseClasses.exists(p => p == SerializableClass || p == JavaSerializableClass)
      || hasAnnotation(SerializableAttr) // last part can be removed, @serializable annotation is deprecated
    )
    def hasBridgeAnnotation = hasAnnotation(BridgeClass)
    def isDeprecated        = hasAnnotation(DeprecatedAttr)
    def deprecationMessage  = getAnnotation(DeprecatedAttr) flatMap (_ stringArg 0)
    def deprecationVersion  = getAnnotation(DeprecatedAttr) flatMap (_ stringArg 1)
    def deprecatedParamName = getAnnotation(DeprecatedNameAttr) flatMap (_ symbolArg 0)

    // !!! when annotation arguments are not literal strings, but any sort of
    // assembly of strings, there is a fair chance they will turn up here not as
    // Literal(const) but some arbitrary AST.  However nothing in the compiler
    // prevents someone from writing a @migration annotation with a calculated
    // string.  So this needs attention.  For now the fact that migration is
    // private[scala] ought to provide enough protection.
    def hasMigrationAnnotation = hasAnnotation(MigrationAnnotationClass)
    def migrationMessage    = getAnnotation(MigrationAnnotationClass) flatMap { _.stringArg(0) }
    def migrationVersion    = getAnnotation(MigrationAnnotationClass) flatMap { _.stringArg(1) }
    def elisionLevel        = getAnnotation(ElidableMethodClass) flatMap { _.intArg(0) }
    def implicitNotFoundMsg = getAnnotation(ImplicitNotFoundClass) flatMap { _.stringArg(0) }

    /** Is this symbol an accessor method for outer? */
    final def isOuterAccessor = {
      hasFlag(STABLE | SYNTHETIC) &&
      originalName == nme.OUTER
    }

    /** Is this symbol an accessor method for outer? */
    final def isOuterField = {
      hasFlag(SYNTHETIC) &&
      originalName == nme.OUTER_LOCAL
    }

    /** Does this symbol denote a stable value? */
    final def isStable =
      isTerm &&
      !isMutable &&
      (!hasFlag(METHOD | BYNAMEPARAM) || hasFlag(STABLE)) &&
      !(tpe.isVolatile && !hasAnnotation(uncheckedStableClass))

    // def isVirtualClass = hasFlag(DEFERRED) && isClass
    // def isVirtualTrait = hasFlag(DEFERRED) && isTrait
    // def isLiftedMethod = hasAllFlags(METHOD | LIFTED)
    def isCaseClass    = isClass && isCase

    /** Does this symbol denote the primary constructor of its enclosing class? */
    final def isPrimaryConstructor =
      isConstructor && owner.primaryConstructor == this

    /** Does this symbol denote an auxiliary constructor of its enclosing class? */
    final def isAuxiliaryConstructor =
      isConstructor && !isPrimaryConstructor

    /** Is this symbol a synthetic apply or unapply method in a companion object of a case class? */
    final def isCaseApplyOrUnapply =
      isMethod && isCase && isSynthetic

    /** Is this symbol a trait which needs an implementation class? */
    final def needsImplClass: Boolean =
      isTrait && (!isInterface || hasFlag(lateINTERFACE)) && !isImplClass

    /** Is this a symbol which exists only in the implementation class, not in its trait? */
    final def isImplOnly: Boolean =
      hasFlag(PRIVATE) ||
      (owner.isImplClass || owner.isTrait) &&
      ((hasFlag(notPRIVATE | LIFTED) && !hasFlag(ACCESSOR | SUPERACCESSOR | MODULE) || isConstructor) ||
       (hasFlag(LIFTED) && isModule && isMethod))

    /** Is this symbol a module variable?
     *  This used to have to test for MUTABLE to distinguish the overloaded
     *  MODULEVAR/SYNTHETICMETH flag, but now SYNTHETICMETH is gone.
     */
    final def isModuleVar = hasFlag(MODULEVAR)

    /** Is this symbol static (i.e. with no outer instance)? */
    def isStatic = (this hasFlag STATIC) || owner.isStaticOwner

    /** Is this symbol a static constructor? */
    final def isStaticConstructor: Boolean =
      isStaticMember && isClassConstructor

    /** Is this symbol a static member of its class? (i.e. needs to be implemented as a Java static?) */
    final def isStaticMember: Boolean =
      hasFlag(STATIC) || owner.isImplClass

    /** Does this symbol denote a class that defines static symbols? */
    final def isStaticOwner: Boolean =
      isPackageClass || isModuleClass && isStatic

    def isTopLevelModule = hasFlag(MODULE) && owner.isPackageClass

    /** Is this symbol effectively final? I.e, it cannot be overridden */
    final def isEffectivelyFinal: Boolean = (
         (this hasFlag FINAL | PACKAGE)
      || isModuleOrModuleClass && (owner.isPackageClass || !settings.overrideObjects.value)
      || isTerm && (
             isPrivate
          || isLocal
          || owner.isClass && owner.isEffectivelyFinal
      )
    )

    /** Is this symbol locally defined? I.e. not accessed from outside `this` instance */
    final def isLocal: Boolean = owner.isTerm

    /** Is this symbol a constant? */
    final def isConstant: Boolean = isStable && isConstantType(tpe.resultType)

    /** Is this class nested in another class or module (not a package)? */
    def isNestedClass = false

    /** Is this class locally defined?
     *  A class is local, if
     *   - it is anonymous, or
     *   - its owner is a value
     *   - it is defined within a local class
     */
    def isLocalClass = false

    def isStableClass = false

/* code for fixing nested objects
    override final def isModuleClass: Boolean =
      super.isModuleClass && !isExpandedModuleClass
*/
    /** Is this class or type defined as a structural refinement type?
     */
    final def isStructuralRefinement: Boolean =
      (isClass || isType || isModule) && info.normalize/*.underlying*/.isStructuralRefinement

    final def isStructuralRefinementMember = owner.isStructuralRefinement && isPossibleInRefinement && isPublic
    final def isPossibleInRefinement       = !isConstructor && !isOverridingSymbol

    /** Is this symbol a member of class `clazz`? */
    def isMemberOf(clazz: Symbol) =
      clazz.info.member(name).alternatives contains this

    /** A a member of class `base` is incomplete if
     *  (1) it is declared deferred or
     *  (2) it is abstract override and its super symbol in `base` is
     *      nonexistent or incomplete.
     *
     *  @param base ...
     *  @return     ...
     */
    final def isIncompleteIn(base: Symbol): Boolean =
      this.isDeferred ||
      (this hasFlag ABSOVERRIDE) && {
        val supersym = superSymbol(base)
        supersym == NoSymbol || supersym.isIncompleteIn(base)
      }

    // Does not always work if the rawInfo is a SourcefileLoader, see comment
    // in "def coreClassesFirst" in Global.
    def exists = !owner.isPackageClass || { rawInfo.load(this); rawInfo != NoType }

    final def isInitialized: Boolean =
      validTo != NoPeriod

    /** The variance of this symbol as an integer */
    final def variance: Int =
      if (isCovariant) 1
      else if (isContravariant) -1
      else 0


    /** The sequence number of this parameter symbol among all type
     *  and value parameters of symbol's owner. -1 if symbol does not
     *  appear among the parameters of its owner.
     */
    def paramPos: Int = {
      def searchIn(tpe: Type, base: Int): Int = {
        def searchList(params: List[Symbol], fallback: Type): Int = {
          val idx = params indexOf this
          if (idx >= 0) idx + base
          else searchIn(fallback, base + params.length)
        }
        tpe match {
          case PolyType(tparams, res) => searchList(tparams, res)
          case MethodType(params, res) => searchList(params, res)
          case _ => -1
        }
      }
      searchIn(owner.info, 0)
    }

// ------ owner attribute --------------------------------------------------------------

    def owner: Symbol = rawowner
    // TODO - don't allow the owner to be changed without checking invariants, at least
    // when under some flag. Define per-phase invariants for owner/owned relationships,
    // e.g. after flatten all classes are owned by package classes, there are lots and
    // lots of these to be declared (or more realistically, discovered.)
    def owner_=(owner: Symbol) {
      // don't keep the original owner in presentation compiler runs
      // (the map will grow indefinitely, and the only use case is the
      // backend).
      if (!forInteractive) {
        if (originalOwner contains this) ()
        else originalOwner(this) = rawowner
      }
      assert(!inReflexiveMirror, "owner_= is not thread-safe; cannot be run in reflexive code")
      if (traceSymbolActivity)
        traceSymbols.recordNewSymbolOwner(this, owner)
      _rawowner = owner
    }

    def ownerChain: List[Symbol] = this :: owner.ownerChain
    def originalOwnerChain: List[Symbol] = this :: originalOwner.getOrElse(this, rawowner).originalOwnerChain

    // Non-classes skip self and return rest of owner chain; overridden in ClassSymbol.
    def enclClassChain: List[Symbol] = owner.enclClassChain

    def ownersIterator: Iterator[Symbol] = new Iterator[Symbol] {
      private var current = Symbol.this
      def hasNext = current ne NoSymbol
      def next = { val r = current; current = current.owner; r }
    }

    /** Same as `ownerChain contains sym` but more efficient, and
     *  with a twist for refinement classes (see RefinementClassSymbol.)
     */
    def hasTransOwner(sym: Symbol): Boolean = {
      var o = this
      while ((o ne sym) && (o ne NoSymbol)) o = o.owner
      (o eq sym)
    }

// ------ name attribute --------------------------------------------------------------

    def name: Name = rawname

    // TODO - don't allow names to be renamed in this unstructured a fashion.
    // Rename as little as possible.  Enforce invariants on all renames.
    def name_=(name: Name) {
      if (name != rawname) {
        if (owner.isClass) {
          var ifs = owner.infos
          while (ifs != null) {
            ifs.info.decls.rehash(this, name)
            ifs = ifs.prev
          }
        }
        _rawname = name
      }
    }

    /** If this symbol has an expanded name, its original name, otherwise its name itself.
     *  @see expandName
     */
    def originalName: Name = nme.originalName(name)

    /** The name of the symbol before decoding, e.g. `\$eq\$eq` instead of `==`.
     */
    def encodedName: String = name.toString

    /** The decoded name of the symbol, e.g. `==` instead of `\$eq\$eq`.
     */
    def decodedName: String = nme.dropLocalSuffix(name).decode

    private def addModuleSuffix(n: Name): Name =
      if (needsModuleSuffix) n append nme.MODULE_SUFFIX_STRING else n

    def moduleSuffix: String = (
      if (needsModuleSuffix) nme.MODULE_SUFFIX_STRING
      else ""
    )
    /** Whether this symbol needs nme.MODULE_SUFFIX_STRING (aka $) appended on the java platform.
     */
    def needsModuleSuffix = (
         hasModuleFlag
      && !isMethod
      && !isImplClass
      && !isJavaDefined
    )
    /** These should be moved somewhere like JavaPlatform.
     */
    def javaSimpleName: Name = addModuleSuffix(nme.dropLocalSuffix(simpleName))
    def javaBinaryName: Name = addModuleSuffix(fullNameInternal('/'))
    def javaClassName: String  = addModuleSuffix(fullNameInternal('.')).toString

    /** The encoded full path name of this symbol, where outer names and inner names
     *  are separated by `separator` characters.
     *  Never translates expansions of operators back to operator symbol.
     *  Never adds id.
     *  Drops package objects.
     */
    final def fullName(separator: Char): String = fullNameAsName(separator).toString

    /** Doesn't drop package objects, for those situations (e.g. classloading)
     *  where the true path is needed.
     */
    private def fullNameInternal(separator: Char): Name = (
      if (isRoot || isRootPackage || this == NoSymbol) name
      else if (owner.isEffectiveRoot) name
      else effectiveOwner.enclClass.fullNameAsName(separator) append separator append name
    )

    def fullNameAsName(separator: Char): Name = nme.dropLocalSuffix(fullNameInternal(separator))

    /** The encoded full path name of this symbol, where outer names and inner names
     *  are separated by periods.
     */
    final def fullName: String = fullName('.')

    /**
     *  Symbol creation implementations.
     */
    protected def createTypeSymbol(name: TypeName, pos: Position, newFlags: Long): TypeSymbol =
      new TypeSymbol(this, pos, name) { } initFlags newFlags

    protected def createAbstractTypeSymbol(name: TypeName, pos: Position, newFlags: Long): AbstractTypeSymbol =
      new AbstractTypeSymbol(this, pos, name) initFlags newFlags

    protected def createAliasTypeSymbol(name: TypeName, pos: Position, newFlags: Long): AliasTypeSymbol =
      new AliasTypeSymbol(this, pos, name) initFlags newFlags

    protected def createTypeSkolemSymbol(name: TypeName, origin: AnyRef, pos: Position, newFlags: Long): TypeSkolem =
      new TypeSkolem(this, pos, name, origin) initFlags newFlags

    protected def createClassSymbol(name: TypeName, pos: Position, newFlags: Long): ClassSymbol =
      new ClassSymbol(this, pos, name) initFlags newFlags

    protected def createModuleClassSymbol(name: TypeName, pos: Position, newFlags: Long): ModuleClassSymbol =
      new ModuleClassSymbol(this, pos, name) initFlags newFlags

    protected def createPackageClassSymbol(name: TypeName, pos: Position, newFlags: Long): PackageClassSymbol =
      new PackageClassSymbol(this, pos, name) initFlags newFlags

    protected def createRefinementClassSymbol(pos: Position, newFlags: Long): RefinementClassSymbol =
      new RefinementClassSymbol(this, pos) initFlags newFlags

    protected def createTermSymbol(name: TermName, pos: Position, newFlags: Long): TermSymbol =
      new TermSymbol(this, pos, name) initFlags newFlags

    protected def createMethodSymbol(name: TermName, pos: Position, newFlags: Long): MethodSymbol =
      new MethodSymbol(this, pos, name) initFlags newFlags

    protected def createModuleSymbol(name: TermName, pos: Position, newFlags: Long): ModuleSymbol =
      new ModuleSymbol(this, pos, name) initFlags newFlags

    protected def createPackageSymbol(name: TermName, pos: Position, newFlags: Long): PackageSymbol =
      new PackageSymbol(this, pos, name) initFlags newFlags

    protected def createValueParameterSymbol(name: TermName, pos: Position, newFlags: Long): TermSymbol =
      new TermSymbol(this, pos, name) initFlags newFlags

    protected def createValueMemberSymbol(name: TermName, pos: Position, newFlags: Long): TermSymbol =
      new TermSymbol(this, pos, name) initFlags newFlags

    /** The class or term up to which this symbol is accessible,
     *  or RootClass if it is public.  As java protected statics are
     *  otherwise completely inaccessible in scala, they are treated
     *  as public.
     */
    def accessBoundary(base: Symbol): Symbol = {
      if (hasFlag(PRIVATE) || isLocal) owner
      else if (hasAllFlags(PROTECTED | STATIC | JAVA)) RootClass
      else if (hasAccessBoundary && !phase.erasedTypes) privateWithin
      else if (hasFlag(PROTECTED)) base
      else RootClass
    }

    def isLessAccessibleThan(other: Symbol): Boolean = {
      val tb = this.accessBoundary(owner)
      val ob1 = other.accessBoundary(owner)
      val ob2 = ob1.linkedClassOfClass
      var o = tb
      while (o != NoSymbol && o != ob1 && o != ob2) {
        o = o.owner
      }
      o != NoSymbol && o != tb
    }

    /** See comment in HasFlags for how privateWithin combines with flags.
     */
    private[this] var _privateWithin: Symbol = _
    def privateWithin = _privateWithin
    def privateWithin_=(sym: Symbol) { _privateWithin = sym }
    def setPrivateWithin(sym: Symbol): this.type = { privateWithin_=(sym) ; this }

    /** Does symbol have a private or protected qualifier set? */
    final def hasAccessBoundary = (privateWithin != null) && (privateWithin != NoSymbol)

// ------ info and type -------------------------------------------------------------------

    private[Symbols] var infos: TypeHistory = null

    /** Get type. The type of a symbol is:
     *  for a type symbol, the type corresponding to the symbol itself,
     *    @M you should use tpeHK for a type symbol with type parameters if
     *       the kind of the type need not be *, as tpe introduces dummy arguments
     *       to generate a type of kind *
     *  for a term symbol, its usual type.
     *  See the tpe/tpeHK overrides in TypeSymbol for more.
     */
    def tpe: Type = info
    def tpeHK: Type = tpe

    /** Get type info associated with symbol at current phase, after
     *  ensuring that symbol is initialized (i.e. type is completed).
     */
    def info: Type = try {
      var cnt = 0
      while (validTo == NoPeriod) {
        //if (settings.debug.value) System.out.println("completing " + this);//DEBUG
        assert(infos ne null, this.name)
        assert(infos.prev eq null, this.name)
        val tp = infos.info
        //if (settings.debug.value) System.out.println("completing " + this.rawname + tp.getClass());//debug

        if ((_rawflags & LOCKED) != 0L) { // rolled out once for performance
          lock {
            setInfo(ErrorType)
            throw CyclicReference(this, tp)
          }
        } else {
          _rawflags |= LOCKED
//          activeLocks += 1
 //         lockedSyms += this
        }
        val current = phase
        try {
          phase = phaseOf(infos.validFrom)
          tp.complete(this)
        } finally {
          unlock()
          phase = current
        }
        cnt += 1
        // allow for two completions:
        //   one: sourceCompleter to LazyType, two: LazyType to completed type
        if (cnt == 3) abort("no progress in completing " + this + ":" + tp)
      }
      rawInfo
    }
    catch {
      case ex: CyclicReference =>
        debugwarn("... hit cycle trying to complete " + this.fullLocationString)
        throw ex
    }

    def info_=(info: Type) {
      assert(info ne null)
      infos = TypeHistory(currentPeriod, info, null)
      unlock()
      _validTo = if (info.isComplete) currentPeriod else NoPeriod
    }

    /** Set initial info. */
    def setInfo(info: Type): this.type                      = { info_=(info); this }
    /** Modifies this symbol's info in place. */
    def modifyInfo(f: Type => Type): this.type              = setInfo(f(info))
    /** Substitute second list of symbols for first in current info. */
    def substInfo(syms0: List[Symbol], syms1: List[Symbol]): this.type =
      if (syms0.isEmpty) this
      else modifyInfo(_.substSym(syms0, syms1))

    def setInfoOwnerAdjusted(info: Type): this.type = setInfo(info atOwner this)

    /** Set the info and enter this symbol into the owner's scope. */
    def setInfoAndEnter(info: Type): this.type = {
      setInfo(info)
      owner.info.decls enter this
      this
    }

    /** Set new info valid from start of this phase. */
    def updateInfo(info: Type): Symbol = {
      assert(phaseId(infos.validFrom) <= phase.id)
      if (phaseId(infos.validFrom) == phase.id) infos = infos.prev
      infos = TypeHistory(currentPeriod, info, infos)
      _validTo = if (info.isComplete) currentPeriod else NoPeriod
      this
    }

    def hasRawInfo: Boolean = infos ne null
    def hasCompleteInfo = hasRawInfo && rawInfo.isComplete

    /** Return info without checking for initialization or completing */
    def rawInfo: Type = {
      var infos = this.infos
      assert(infos != null)
      val curPeriod = currentPeriod
      val curPid = phaseId(curPeriod)

      if (validTo != NoPeriod) {
        // skip any infos that concern later phases
        while (curPid < phaseId(infos.validFrom) && infos.prev != null)
          infos = infos.prev

        if (validTo < curPeriod) {
          // adapt any infos that come from previous runs
          val current = phase
          try {
            infos = adaptInfos(infos)

            //assert(runId(validTo) == currentRunId, name)
            //assert(runId(infos.validFrom) == currentRunId, name)

            if (validTo < curPeriod) {
              var itr = infoTransformers.nextFrom(phaseId(validTo))
              infoTransformers = itr; // caching optimization
              while (itr.pid != NoPhase.id && itr.pid < current.id) {
                phase = phaseWithId(itr.pid)
                val info1 = itr.transform(this, infos.info)
                if (info1 ne infos.info) {
                  infos = TypeHistory(currentPeriod + 1, info1, infos)
                  this.infos = infos
                }
                _validTo = currentPeriod + 1 // to enable reads from same symbol during info-transform
                itr = itr.next
              }
              _validTo = if (itr.pid == NoPhase.id) curPeriod
                         else period(currentRunId, itr.pid)
            }
          } finally {
            phase = current
          }
        }
      }
      infos.info
    }

    // adapt to new run in fsc.
    private def adaptInfos(infos: TypeHistory): TypeHistory = {
      assert(!inReflexiveMirror)
      if (infos == null || runId(infos.validFrom) == currentRunId) {
        infos
      } else {
        val prev1 = adaptInfos(infos.prev)
        if (prev1 ne infos.prev) prev1
        else {
          val pid = phaseId(infos.validFrom)

          _validTo = period(currentRunId, pid)
          phase   = phaseWithId(pid)

          val info1 = (
            if (isPackageClass) infos.info
            else adaptToNewRunMap(infos.info)
          )
          if (info1 eq infos.info) {
            infos.validFrom = validTo
            infos
          } else {
            this.infos = TypeHistory(validTo, info1, prev1)
            this.infos
          }
        }
      }
    }

    /** Initialize the symbol */
    final def initialize: this.type = {
      if (!isInitialized) info
      this
    }

    /** Was symbol's type updated during given phase? */
    final def isUpdatedAt(pid: Phase#Id): Boolean = {
      assert(!inReflexiveMirror)
      var infos = this.infos
      while ((infos ne null) && phaseId(infos.validFrom) != pid + 1) infos = infos.prev
      infos ne null
    }

    /** Was symbol's type updated during given phase? */
    final def hasTypeAt(pid: Phase#Id): Boolean = {
      assert(!inReflexiveMirror)
      var infos = this.infos
      while ((infos ne null) && phaseId(infos.validFrom) > pid) infos = infos.prev
      infos ne null
    }

    /** Modify term symbol's type so that a raw type C is converted to an existential C[_]
     *
     * This is done in checkAccessible and overriding checks in refchecks
     * We can't do this on class loading because it would result in infinite cycles.
     */
    final def cookJavaRawInfo() {
      if (hasFlag(TRIEDCOOKING)) return else setFlag(TRIEDCOOKING) // only try once...
      val oldInfo = info
      doCookJavaRawInfo()
    }

    protected def doCookJavaRawInfo(): Unit

    /** The type constructor of a symbol is:
     *  For a type symbol, the type corresponding to the symbol itself,
     *  excluding parameters.
     *  Not applicable for term symbols.
     */
    def typeConstructor: Type =
      abort("typeConstructor inapplicable for " + this)

    /** The logic approximately boils down to finding the most recent phase
     *  which immediately follows any of parser, namer, typer, or erasure.
     *  In effect that means this will return one of:
     *
     *    - packageobjects (follows namer) 
     *    - superaccessors (follows typer)
     *    - lazyvals       (follows erasure)
     *    - null
     */
    private def unsafeTypeParamPhase = {
      var ph = phase
      while (ph.prev.keepsTypeParams)
        ph = ph.prev

      ph
    }
    /** The type parameters of this symbol, without ensuring type completion.
     *  assumption: if a type starts out as monomorphic, it will not acquire
     *  type parameters later.
     */
    def unsafeTypeParams: List[Symbol] =
      if (isMonomorphicType) Nil
      else atPhase(unsafeTypeParamPhase)(rawInfo.typeParams)

    /** The type parameters of this symbol.
     *  assumption: if a type starts out as monomorphic, it will not acquire
     *  type parameters later.
     */
    def typeParams: List[Symbol] =
      if (isMonomorphicType) Nil
      else {
        // analogously to the "info" getter, here we allow for two completions:
        //   one: sourceCompleter to LazyType, two: LazyType to completed type
        if (validTo == NoPeriod)
          atPhase(phaseOf(infos.validFrom))(rawInfo load this)
        if (validTo == NoPeriod)
          atPhase(phaseOf(infos.validFrom))(rawInfo load this)

        rawInfo.typeParams
      }

    /** The value parameter sections of this symbol.
     */
    def paramss: List[List[Symbol]] = info.paramss
    def hasParamWhich(cond: Symbol => Boolean) = mexists(paramss)(cond)

    /** The least proper supertype of a class; includes all parent types
     *  and refinement where needed. You need to compute that in a situation like this:
     *  {
     *    class C extends P { ... }
     *    new C
     *  }
     */
    def classBound: Type = {
      val tp = refinedType(info.parents, owner)
      val thistp = tp.typeSymbol.thisType
      val oldsymbuf = new ListBuffer[Symbol]
      val newsymbuf = new ListBuffer[Symbol]
      for (sym <- info.decls) {
        // todo: what about public references to private symbols?
        if (sym.isPublic && !sym.isConstructor) {
          oldsymbuf += sym
          newsymbuf += (
            if (sym.isClass)
              tp.typeSymbol.newAbstractType(sym.name.toTypeName, sym.pos).setInfo(sym.existentialBound)
            else
              sym.cloneSymbol(tp.typeSymbol))
        }
      }
      val oldsyms = oldsymbuf.toList
      val newsyms = newsymbuf.toList
      for (sym <- newsyms) {
        addMember(thistp, tp, sym modifyInfo (_ substThisAndSym(this, thistp, oldsyms, newsyms)))
      }
      tp
    }

    /** If we quantify existentially over this symbol,
     *  the bound of the type variable that stands for it
     *  pre: symbol is a term, a class, or an abstract type (no alias type allowed)
     */
    def existentialBound: Type

    /** Reset symbol to initial state
     */
    def reset(completer: Type) {
      resetFlags()
      infos = null
      _validTo = NoPeriod
      //limit = NoPhase.id
      setInfo(completer)
    }

    /**
     * Adds the interface scala.Serializable to the parents of a ClassInfoType.
     * Note that the tree also has to be updated accordingly.
     */
    def makeSerializable() {
      info match {
        case ci @ ClassInfoType(_, _, _) =>
          updateInfo(ci.copy(parents = ci.parents :+ SerializableClass.tpe))
        case i =>
          abort("Only ClassInfoTypes can be made serializable: "+ i)
      }
    }

// ----- setters implemented in selected subclasses -------------------------------------

    def typeOfThis_=(tp: Type)       { throw new UnsupportedOperationException("typeOfThis_= inapplicable for " + this) }
    def sourceModule_=(sym: Symbol)  { throw new UnsupportedOperationException("sourceModule_= inapplicable for " + this) }
    def addChild(sym: Symbol)        { throw new UnsupportedOperationException("addChild inapplicable for " + this) }

// ----- annotations ------------------------------------------------------------

    // null is a marker that they still need to be obtained.
    private[this] var _annotations: List[AnnotationInfo] = Nil

    def annotationsString = if (annotations.isEmpty) "" else annotations.mkString("(", ", ", ")")

    /** After the typer phase (before, look at the definition's Modifiers), contains
     *  the annotations attached to member a definition (class, method, type, field).
     */
    def annotations: List[AnnotationInfo] = {
      // Necessary for reflection, see SI-5423
      if (inReflexiveMirror)
        initialize

      _annotations
    }

    def setAnnotations(annots: List[AnnotationInfo]): this.type = {
      _annotations = annots
      this
    }

    def withAnnotations(annots: List[AnnotationInfo]): this.type =
      setAnnotations(annots ::: annotations)

    def withoutAnnotations: this.type =
      setAnnotations(Nil)

    def filterAnnotations(p: AnnotationInfo => Boolean): this.type =
      setAnnotations(annotations filter p)

    def addAnnotation(annot: AnnotationInfo): this.type =
      setAnnotations(annot :: annotations)

    // Convenience for the overwhelmingly common case
    def addAnnotation(sym: Symbol, args: Tree*): this.type =
      addAnnotation(AnnotationInfo(sym.tpe, args.toList, Nil))

// ------ comparisons ----------------------------------------------------------------

    /** A total ordering between symbols that refines the class
     *  inheritance graph (i.e. subclass.isLess(superclass) always holds).
     *  the ordering is given by: (_.isType, -_.baseTypeSeq.length) for type symbols, followed by `id`.
     */
    final def isLess(that: Symbol): Boolean = {
      def baseTypeSeqLength(sym: Symbol) =
        if (sym.isAbstractType) 1 + sym.info.bounds.hi.baseTypeSeq.length
        else sym.info.baseTypeSeq.length
      if (this.isType)
        (that.isType &&
         { val diff = baseTypeSeqLength(this) - baseTypeSeqLength(that)
           diff > 0 || diff == 0 && this.id < that.id })
      else
        that.isType || this.id < that.id
    }

    /** A partial ordering between symbols.
     *  (this isNestedIn that) holds iff this symbol is defined within
     *  a class or method defining that symbol
     */
    final def isNestedIn(that: Symbol): Boolean =
      owner == that || owner != NoSymbol && (owner isNestedIn that)

    /** Is this class symbol a subclass of that symbol,
     *  and is this class symbol also different from Null or Nothing? */
    def isNonBottomSubClass(that: Symbol): Boolean = false

    /** Overridden in NullClass and NothingClass for custom behavior.
     */
    def isSubClass(that: Symbol) = isNonBottomSubClass(that)

    final def isNumericSubClass(that: Symbol): Boolean =
      definitions.isNumericSubClass(this, that)

    final def isWeakSubClass(that: Symbol) =
      isSubClass(that) || isNumericSubClass(that)

// ------ overloaded alternatives ------------------------------------------------------

    def alternatives: List[Symbol] =
      if (isOverloaded) info.asInstanceOf[OverloadedType].alternatives
      else List(this)

    def filter(cond: Symbol => Boolean): Symbol =
      if (isOverloaded) {
        val alts = alternatives
        val alts1 = alts filter cond
        if (alts1 eq alts) this
        else if (alts1.isEmpty) NoSymbol
        else if (alts1.tail.isEmpty) alts1.head
        else owner.newOverloaded(info.prefix, alts1)
      }
      else if (cond(this)) this
      else NoSymbol

    def suchThat(cond: Symbol => Boolean): Symbol = {
      val result = filter(cond)
      assert(!result.isOverloaded, result.alternatives)
      result
    }

// ------ cloneing -------------------------------------------------------------------

    /** A clone of this symbol. */
    final def cloneSymbol: Symbol =
      cloneSymbol(owner)

    /** A clone of this symbol, but with given owner. */
    final def cloneSymbol(newOwner: Symbol): Symbol =
      cloneSymbol(newOwner, _rawflags)
    final def cloneSymbol(newOwner: Symbol, newFlags: Long): Symbol =
      cloneSymbol(newOwner, newFlags, nme.NO_NAME)
    final def cloneSymbol(newOwner: Symbol, newFlags: Long, newName: Name): Symbol = {
      val clone = cloneSymbolImpl(newOwner, newFlags)
      ( clone
          setPrivateWithin privateWithin
          setInfo (this.info cloneInfo clone)
          setAnnotations this.annotations
      )
      if (clone.thisSym != clone)
        clone.typeOfThis = (clone.typeOfThis cloneInfo clone)
      if (newName != nme.NO_NAME)
        clone.name = newName

      clone
    }

    /** Internal method to clone a symbol's implementation with the given flags and no info. */
    def cloneSymbolImpl(owner: Symbol, newFlags: Long): Symbol

// ------ access to related symbols --------------------------------------------------

    /** The next enclosing class. */
    def enclClass: Symbol = if (isClass) this else owner.enclClass

    /** The next enclosing method. */
    def enclMethod: Symbol = if (isSourceMethod) this else owner.enclMethod

    /** The primary constructor of a class. */
    def primaryConstructor: Symbol = NoSymbol

    /** The self symbol (a TermSymbol) of a class with explicit self type, or else the
     *  symbol itself (a TypeSymbol).
     *
     *  WARNING: you're probably better off using typeOfThis, as it's more uniform across classes with and without self variables.
     *
     *  Example by Paul:
     *   scala> trait Foo1 { }
     *   scala> trait Foo2 { self => }
     *   scala> intp("Foo1").thisSym
     *   res0: $r.intp.global.Symbol = trait Foo1
     *
     *   scala> intp("Foo2").thisSym
     *   res1: $r.intp.global.Symbol = value self
     *
     *  Martin says: The reason `thisSym' is `this' is so that thisType can be this.thisSym.tpe.
     *  It's a trick to shave some cycles off.
     *
     *  Morale: DO:    if (clazz.typeOfThis.typeConstructor ne clazz.typeConstructor) ...
     *          DON'T: if (clazz.thisSym ne clazz) ...
     *
     */
    def thisSym: Symbol = this

    /** The type of `this` in a class, or else the type of the symbol itself. */
    def typeOfThis = thisSym.tpe

    /** If symbol is a class, the type <code>this.type</code> in this class,
     * otherwise <code>NoPrefix</code>.
     * We always have: thisType <:< typeOfThis
     */
    def thisType: Type = NoPrefix

    /** For a case class, the symbols of the accessor methods, one for each
     *  argument in the first parameter list of the primary constructor.
     *  The empty list for all other classes.
     */
    final def caseFieldAccessors: List[Symbol] =
      info.decls filter (_.isCaseAccessorMethod) toList

    final def constrParamAccessors: List[Symbol] =
      info.decls.toList filter (sym => !sym.isMethod && sym.isParamAccessor)

    /** The symbol accessed by this accessor (getter or setter) function. */
    final def accessed: Symbol = accessed(owner.info)

    /** The symbol accessed by this accessor function, but with given owner type. */
    final def accessed(ownerTp: Type): Symbol = {
      assert(hasAccessorFlag, this)
      ownerTp decl nme.getterToLocal(getterName.toTermName)
    }

    /** The module corresponding to this module class (note that this
     *  is not updated when a module is cloned), or NoSymbol if this is not a ModuleClass.
     */
    def sourceModule: Symbol = NoSymbol
    // if (isModuleClass) companionModule else NoSymbol
    // NoSymbol

    /** The implementation class of a trait.  If available it will be the
     *  symbol with the same owner, and the name of this symbol with $class
     *  appended to it.
     */
    final def implClass: Symbol = owner.info.decl(nme.implClassName(name))

    /** The class that is logically an outer class of given `clazz`.
     *  This is the enclosing class, except for classes defined locally to constructors,
     *  where it is the outer class of the enclosing class.
     */
    final def outerClass: Symbol =
      if (owner.isClass) owner
      else if (isClassLocalToConstructor) owner.enclClass.outerClass
      else owner.outerClass

    /** For a paramaccessor: a superclass paramaccessor for which this symbol
     *  is an alias, NoSymbol for all others.
     */
    def alias: Symbol = NoSymbol

    /** For a lazy value, its lazy accessor. NoSymbol for all others. */
    def lazyAccessor: Symbol = NoSymbol

    /** If this is a lazy value, the lazy accessor; otherwise this symbol. */
    def lazyAccessorOrSelf: Symbol = if (isLazy) lazyAccessor else this

    /** If this is an accessor, the accessed symbol.  Otherwise, this symbol. */
    def accessedOrSelf: Symbol = if (hasAccessorFlag) accessed else this

    /** For an outer accessor: The class from which the outer originates.
     *  For all other symbols: NoSymbol
     */
    def outerSource: Symbol = NoSymbol

    /** The superclass of this class. */
    def superClass: Symbol = if (info.parents.isEmpty) NoSymbol else info.parents.head.typeSymbol
    def parentSymbols: List[Symbol] = info.parents map (_.typeSymbol)

    /** The directly or indirectly inherited mixins of this class
     *  except for mixin classes inherited by the superclass. Mixin classes appear
     *  in linearization order.
     */
    def mixinClasses: List[Symbol] = {
      val sc = superClass
      ancestors takeWhile (sc ne)
    }

    /** All directly or indirectly inherited classes. */
    def ancestors: List[Symbol] = info.baseClasses drop 1

    /** The package class containing this symbol, or NoSymbol if there
     *  is not one. */
    def enclosingPackageClass: Symbol = {
      var packSym = this.owner
      while (packSym != NoSymbol && !packSym.isPackageClass)
        packSym = packSym.owner
      packSym
    }

    /** The package containing this symbol, or NoSymbol if there
     *  is not one. */
    def enclosingPackage: Symbol = enclosingPackageClass.companionModule

    /** Return the original enclosing method of this symbol. It should return
     *  the same thing as enclMethod when called before lambda lift,
     *  but it preserves the original nesting when called afterwards.
     *
     *  @note This method is NOT available in the presentation compiler run. The
     *        originalOwner map is not populated for memory considerations (the symbol
     *        may hang on to lazy types and in turn to whole (outdated) compilation units.
     */
    def originalEnclosingMethod: Symbol = {
      assert(!forInteractive, "originalOwner is not kept in presentation compiler runs.")
      if (isMethod) this
      else {
        val owner = originalOwner.getOrElse(this, rawowner)
        if (isLocalDummy) owner.enclClass.primaryConstructor
        else owner.originalEnclosingMethod
      }
    }

    /** The method or class which logically encloses the current symbol.
     *  If the symbol is defined in the initialization part of a template
     *  this is the template's primary constructor, otherwise it is
     *  the physically enclosing method or class.
     *
     *  Example 1:
     *
     *  def f() { val x = { def g() = ...; g() } }
     *
     *  In this case the owner chain of `g` is `x`, followed by `f` and
     *  `g.logicallyEnclosingMember == f`.
     *
     *  Example 2:
     *
     *  class C {
     *    def <init> = { ... }
     *    val x = { def g() = ...; g() } }
     *  }
     *
     *  In this case the owner chain of `g` is `x`, followed by `C` but
     *  g.logicallyEnclosingMember is the primary constructor symbol `<init>`
     *  (or, for traits: `$init`) of `C`.
     *
     */
    def logicallyEnclosingMember: Symbol =
      if (isLocalDummy) enclClass.primaryConstructor
      else if (isMethod || isClass) this
      else owner.logicallyEnclosingMember

    /** Kept for source compatibility with 2.9. Scala IDE for Eclipse relies on this. */
    @deprecated("Use enclosingTopLevelClass", "2.10.0")
    def toplevelClass: Symbol = enclosingTopLevelClass

    /** The top-level class containing this symbol. */
    def enclosingTopLevelClass: Symbol =
      if (owner.isPackageClass) {
        if (isClass) this else moduleClass
      } else owner.enclosingTopLevelClass

    /** Is this symbol defined in the same scope and compilation unit as `that` symbol? */
    def isCoDefinedWith(that: Symbol) = (
      (this.rawInfo ne NoType) &&
      (this.effectiveOwner == that.effectiveOwner) && {
        !this.effectiveOwner.isPackageClass ||
        (this.sourceFile eq null) ||
        (that.sourceFile eq null) ||
        (this.sourceFile == that.sourceFile) || {
          // recognize companion object in separate file and fail, else compilation
          // appears to succeed but highly opaque errors come later: see bug #1286
          if (this.sourceFile.path != that.sourceFile.path) {
            // The cheaper check can be wrong: do the expensive normalization
            // before failing.
            if (this.sourceFile.canonicalPath != that.sourceFile.canonicalPath)
              throw InvalidCompanions(this, that)
          }

          false
        }
      }
    )

    /** The internal representation of classes and objects:
     *
     *  class Foo is "the class" or sometimes "the plain class"
     * object Foo is "the module"
     * class Foo$ is "the module class" (invisible to the user: it implements object Foo)
     *
     * class Foo  <
     *  ^  ^ (2)   \
     *  |  |  |     \
     *  | (5) |     (3)
     *  |  |  |       \
     * (1) v  v        \
     * object Foo (4)-> > class Foo$
     *
     * (1) companionClass
     * (2) companionModule
     * (3) linkedClassOfClass
     * (4) moduleClass
     * (5) companionSymbol
     */

    /** For a module: the class with the same name in the same package.
     *  For all others: NoSymbol
     *  Note: does not work for classes owned by methods, see Namers.companionClassOf
     *
     *  object Foo  .  companionClass -->  class Foo
     *
     *  !!! linkedClassOfClass depends on companionClass on the module class getting
     *  to the class.  As presently implemented this potentially returns class for
     *  any symbol except NoSymbol.
     */
    def companionClass: Symbol = flatOwnerInfo.decl(name.toTypeName).suchThat(_ isCoDefinedWith this)

    /** For a class: the module or case class factory with the same name in the same package.
     *  For all others: NoSymbol
     *  Note: does not work for modules owned by methods, see Namers.companionModuleOf
     *
     *  class Foo  .  companionModule -->  object Foo
     */
    def companionModule: Symbol = NoSymbol

    /** For a module: its linked class
     *  For a plain class: its linked module or case factory.
     *  Note: does not work for modules owned by methods, see Namers.companionSymbolOf
     *
     *  class Foo  <-- companionSymbol -->  object Foo
     */
    def companionSymbol: Symbol = NoSymbol

    /** For a module class: its linked class
     *   For a plain class: the module class of its linked module.
     *
     *  class Foo  <-- linkedClassOfClass -->  class Foo$
     */
    def linkedClassOfClass: Symbol = NoSymbol

    /**
     * Returns the rawInfo of the owner. If the current phase has flat classes,
     * it first applies all pending type maps to this symbol.
     *
     * assume this is the ModuleSymbol for B in the following definition:
     *   package p { class A { object B { val x = 1 } } }
     *
     * The owner after flatten is "package p" (see "def owner"). The flatten type map enters
     * symbol B in the decls of p. So to find a linked symbol ("object B" or "class B")
     * we need to apply flatten to B first. Fixes #2470.
     */
    protected final def flatOwnerInfo: Type = {
      if (needsFlatClasses)
        info
      owner.rawInfo
    }

    /** If this symbol is an implementation class, its interface, otherwise the symbol itself
     *  The method follows two strategies to determine the interface.
     *   - during or after erasure, it takes the last parent of the implementation class
     *     (which is always the interface, by convention)
     *   - before erasure, it looks up the interface name in the scope of the owner of the class.
     *     This only works for implementation classes owned by other classes or traits.
     *     !!! Why?
     */
    final def toInterface: Symbol =
      if (isImplClass) {
        val result =
          if (phase.next.erasedTypes) {
            assert(!tpe.parents.isEmpty, this)
            tpe.parents.last.typeSymbol
          } else {
            owner.info.decl(nme.interfaceName(name))
          }
        assert(result != NoSymbol, this)
        result
      } else this

    /** The module class corresponding to this module.
     */
    def moduleClass: Symbol = NoSymbol

    /** The non-private symbol whose type matches the type of this symbol
     *  in in given class.
     *
     *  @param ofclazz   The class containing the symbol's definition
     *  @param site      The base type from which member types are computed
     */
    final def matchingSymbol(ofclazz: Symbol, site: Type): Symbol =
      ofclazz.info.nonPrivateDecl(name).filter(sym =>
        !sym.isTerm || (site.memberType(this) matches site.memberType(sym)))

    /** The non-private member of `site` whose type and name match the type of this symbol. */
    final def matchingSymbol(site: Type, admit: Long = 0L): Symbol =
      site.nonPrivateMemberAdmitting(name, admit).filter(sym =>
        !sym.isTerm || (site.memberType(this) matches site.memberType(sym)))

    /** The symbol overridden by this symbol in given class `ofclazz`.
     *
     *  @param ofclazz is a base class of this symbol's owner.
     */
    final def overriddenSymbol(ofclazz: Symbol): Symbol =
      if (isClassConstructor) NoSymbol else matchingSymbol(ofclazz, owner.thisType)

    /** The symbol overriding this symbol in given subclass `ofclazz`.
     *
     *  @param ofclazz is a subclass of this symbol's owner
     */
    final def overridingSymbol(ofclazz: Symbol): Symbol =
      if (isClassConstructor) NoSymbol else matchingSymbol(ofclazz, ofclazz.thisType)

    /** Returns all symbols overriden by this symbol. */
    final def allOverriddenSymbols: List[Symbol] =
      if (!owner.isClass) Nil
      else owner.ancestors map overriddenSymbol filter (_ != NoSymbol)

    /** Equivalent to allOverriddenSymbols.nonEmpty, but more efficient. */
    // !!! When if ever will this answer differ from .isOverride?
    // How/where is the OVERRIDE flag managed, as compared to how checks
    // based on type membership will evaluate?
    def isOverridingSymbol = owner.isClass && (
      owner.ancestors exists (cls => matchingSymbol(cls, owner.thisType) != NoSymbol)
    )
    /** Equivalent to allOverriddenSymbols.head (or NoSymbol if no overrides) but more efficient. */
    def nextOverriddenSymbol: Symbol = {
      if (owner.isClass) owner.ancestors foreach { base =>
        val sym = overriddenSymbol(base)
        if (sym != NoSymbol)
          return sym
      }
      NoSymbol
    }

    /** Returns all symbols overridden by this symbol, plus all matching symbols
     *  defined in parents of the selftype.
     */
    final def extendedOverriddenSymbols: List[Symbol] =
      if (!owner.isClass) Nil
      else owner.thisSym.ancestors map overriddenSymbol filter (_ != NoSymbol)

    /** The symbol accessed by a super in the definition of this symbol when
     *  seen from class `base`. This symbol is always concrete.
     *  pre: `this.owner` is in the base class sequence of `base`.
     */
    final def superSymbol(base: Symbol): Symbol = {
      var bcs = base.info.baseClasses.dropWhile(owner !=).tail
      var sym: Symbol = NoSymbol
      while (!bcs.isEmpty && sym == NoSymbol) {
        if (!bcs.head.isImplClass)
          sym = matchingSymbol(bcs.head, base.thisType).suchThat(!_.isDeferred)
        bcs = bcs.tail
      }
      sym
    }

    /** The getter of this value or setter definition in class `base`, or NoSymbol if
     *  none exists.
     */
    final def getter(base: Symbol): Symbol = base.info.decl(getterName) filter (_.hasAccessorFlag)

    def getterName: TermName = (
      if (isSetter) nme.setterToGetter(name.toTermName)
      else if (nme.isLocalName(name)) nme.localToGetter(name.toTermName)
      else name.toTermName
    )

    /** The setter of this value or getter definition, or NoSymbol if none exists */
    final def setter(base: Symbol): Symbol = setter(base, false)

    final def setter(base: Symbol, hasExpandedName: Boolean): Symbol = {
      var sname = nme.getterToSetter(nme.getterName(name.toTermName))
      if (hasExpandedName) sname = nme.expandedSetterName(sname, base)
      base.info.decl(sname) filter (_.hasAccessorFlag)
    }

    /** Return the accessor method of the first parameter of this class.
     *  or NoSymbol if it does not exist.
     */
    def firstParamAccessor: Symbol = NoSymbol

     /** The case module corresponding to this case class
     *  @pre case class is a member of some other class or package
     */
    final def caseModule: Symbol = {
      var modname = name.toTermName
      if (privateWithin.isClass && !privateWithin.isModuleClass && !hasFlag(EXPANDEDNAME))
        modname = nme.expandedName(modname, privateWithin)
      initialize.owner.info.decl(modname).suchThat(_.isModule)
    }

    /** If this symbol is a type parameter skolem (not an existential skolem!)
     *  its corresponding type parameter, otherwise this */
    def deSkolemize: Symbol = this

    /** If this symbol is an existential skolem the location (a Tree or null)
     *  where it was unpacked. Resulttype is AnyRef because trees are not visible here. */
    def unpackLocation: AnyRef = null

    /** Remove private modifier from symbol `sym`s definition. If `sym` is a
     *  is not a constructor nor a static module rename it by expanding its name to avoid name clashes
     *  @param base  the fully qualified name of this class will be appended if name expansion is needed 
     */
    final def makeNotPrivate(base: Symbol) {
      if (this.isPrivate) {
        setFlag(notPRIVATE)
        // Marking these methods final causes problems for proxies which use subclassing. If people
        // write their code with no usage of final, we probably shouldn't introduce it ourselves
        // unless we know it is safe. ... Unfortunately if they aren't marked final the inliner
        // thinks it can't inline them. So once again marking lateFINAL, and in genjvm we no longer
        // generate ACC_FINAL on "final" methods which are actually lateFINAL.
        if (isMethod && !isDeferred)
          setFlag(lateFINAL)
        if (!isStaticModule && !isClassConstructor) {
          expandName(base)
          if (isModule) moduleClass.makeNotPrivate(base)
        }
      }
    }

    /** Remove any access boundary and clear flags PROTECTED | PRIVATE.
     */
    def makePublic = this setPrivateWithin NoSymbol resetFlag AccessFlags

    /** The first parameter to the first argument list of this method,
     *  or NoSymbol if inapplicable.
     */
    def firstParam = info.params match {
      case p :: _ => p
      case _      => NoSymbol
    }
/* code for fixing nested objects
    def expandModuleClassName() {
      name = newTypeName(name.toString + "$")
    }

    def isExpandedModuleClass: Boolean = name(name.length - 1) == '$'
*/
    def sourceFile: AbstractFileType =
      if (isModule) moduleClass.sourceFile
      else enclosingTopLevelClass.sourceFile

    def sourceFile_=(f: AbstractFileType) {
      abort("sourceFile_= inapplicable for " + this)
    }

    /** If this is a sealed class, its known direct subclasses.
     *  Otherwise, the empty set.
     */
    def children: Set[Symbol] = Set()

    /** Recursively assemble all children of this symbol.
     */
    def sealedDescendants: Set[Symbol] = children.flatMap(_.sealedDescendants) + this

    @inline final def orElse[T](alt: => Symbol): Symbol = if (this ne NoSymbol) this else alt

// ------ toString -------------------------------------------------------------------

    /** A tag which (in the ideal case) uniquely identifies class symbols */
    final def tag: Int = fullName.##

    /** The simple name of this Symbol */
    final def simpleName: Name = name

    /** The String used to order otherwise identical sealed symbols.
     *  This uses data which is stable across runs and variable classpaths
     *  (the initial Name) before falling back on id, which varies depending
     *  on exactly when a symbol is loaded.
     */
    final def sealedSortName: String = initName + "#" + id

    /** String representation of symbol's definition key word */
    final def keyString: String =
      if (isJavaInterface) "interface"
      else if (isTrait) "trait"
      else if (isClass) "class"
      else if (isType && !isParameter) "type"
      else if (isVariable) "var"
      else if (isPackage) "package"
      else if (isModule) "object"
      else if (isSourceMethod) "def"
      else if (isTerm && (!isParameter || isParamAccessor)) "val"
      else ""

    private case class SymbolKind(accurate: String, sanitized: String, abbreviation: String)
    private def symbolKind: SymbolKind = {
      val kind =
        if (isInstanceOf[FreeVar]) ("free variable", "free variable", "FV")
        else if (isPackage) ("package", "package", "PK")
        else if (isPackageClass) ("package class", "package", "PKC")
        else if (isPackageObject) ("package object", "package", "PKO")
        else if (isPackageObjectClass) ("package object class", "package", "PKOC")
        else if (isAnonymousClass) ("anonymous class", "anonymous class", "AC")
        else if (isRefinementClass) ("refinement class", "", "RC")
        else if (isModule) ("module", "object", "MOD")
        else if (isModuleClass) ("module class", "object", "MODC")
        else if (isGetter) ("getter", if (isSourceMethod) "method" else "value", "GET")
        else if (isSetter) ("setter", if (isSourceMethod) "method" else "value", "SET")
        else if (isTerm && isLazy) ("lazy value", "lazy value", "LAZ")
        else if (isVariable) ("field", "variable", "VAR")
        else if (isTrait) ("trait", "trait", "TRT")
        else if (isClass) ("class", "class", "CLS")
        else if (isType) ("type", "type", "TPE")
        else if (isClassConstructor && isPrimaryConstructor) ("primary constructor", "constructor", "PCTOR")
        else if (isClassConstructor) ("constructor", "constructor", "CTOR")
        else if (isSourceMethod) ("method", "method", "METH")
        else if (isTerm) ("value", "value", "VAL")
        else ("", "", "???")
      SymbolKind(kind._1, kind._2, kind._3)
    }

    /** Accurate string representation of symbols' kind, suitable for developers. */
    final def accurateKindString: String =
      symbolKind.accurate

    /** String representation of symbol's kind, suitable for the masses. */
    private def sanitizedKindString: String =
      symbolKind.sanitized

    /** String representation of symbol's kind, suitable for the masses. */
    protected[scala] def abbreviatedKindString: String =
      symbolKind.abbreviation

    final def kindString: String =
      if (settings.debug.value) accurateKindString
      else sanitizedKindString

    /** If the name of the symbol's owner should be used when you care about
     *  seeing an interesting name: in such cases this symbol is e.g. a method
     *  parameter with a synthetic name, a constructor named "this", an object
     *  "package", etc.  The kind string, if non-empty, will be phrased relative
     *  to the name of the owner.
     */
    def hasMeaninglessName = (
         isSetterParameter        // x$1
      || isClassConstructor       // this
      || isRefinementClass        // <refinement>
      || (name == nme.PACKAGE)    // package
    )

    /** String representation of symbol's simple name.
     *  If !settings.debug translates expansions of operators back to operator symbol.
     *  E.g. $eq => =.
     *  If settings.uniqid, adds id.
     *  If settings.Yshowsymkinds, adds abbreviated symbol kind.
     */
    def nameString: String = (
      if (!settings.uniqid.value && !settings.Yshowsymkinds.value) "" + decodedName
      else if (settings.uniqid.value && !settings.Yshowsymkinds.value) decodedName + "#" + id
      else if (!settings.uniqid.value && settings.Yshowsymkinds.value) decodedName + "#" + abbreviatedKindString
      else decodedName + "#" + id + "#" + abbreviatedKindString
    )

    def fullNameString: String = {
      def recur(sym: Symbol): String = {
        if (sym.isRootSymbol || sym == NoSymbol) sym.nameString
        else if (sym.owner.isEffectiveRoot) sym.nameString
        else recur(sym.effectiveOwner.enclClass) + "." + sym.nameString
      }

      recur(this)
    }

    /** If settings.uniqid is set, the symbol's id, else "" */
    final def idString = if (settings.uniqid.value) "#"+id else ""

    /** String representation, including symbol's kind e.g., "class Foo", "method Bar".
     *  If hasMeaninglessName is true, uses the owner's name to disambiguate identity.
     */
    override def toString: String = compose(
      kindString,
      if (hasMeaninglessName) owner.decodedName + idString else nameString
    )

    /** String representation of location.
     */
    def ownsString: String = {
      val owns = effectiveOwner
      if (owns.isClass && !owns.isEmptyPrefix) "" + owns else ""
    }

    /** String representation of location, plus a preposition.  Doesn't do much,
     *  for backward compatibility reasons.
     */
    def locationString: String = ownsString match {
      case ""   => ""
      case s    => " in " + s
    }
    def fullLocationString: String = toString + locationString
    def signatureString: String    = if (hasRawInfo) infoString(rawInfo) else "<_>"

    /** String representation of symbol's definition following its name */
    final def infoString(tp: Type): String = {
      def parents = (
        if (settings.debug.value) parentsString(tp.parents)
        else briefParentsString(tp.parents)
      )
      if (isType) typeParamsString(tp) + (
        if (isClass) " extends " + parents
        else if (isAliasType) " = " + tp.resultType
        else tp.resultType match {
          case rt @ TypeBounds(_, _) => "" + rt
          case rt                    => " <: " + rt
        }
      )
      else if (isModule) "" //  avoid "object X of type X.type"
      else tp match {
        case PolyType(tparams, res)  => typeParamsString(tp) + infoString(res)
        case NullaryMethodType(res)  => infoString(res)
        case MethodType(params, res) => valueParamsString(tp) + infoString(res)
        case _                       => ": " + tp
      }
    }

    def infosString = infos.toString
    def debugLocationString = fullLocationString + " " + debugFlagString

    private def defStringCompose(infoString: String) = compose(
      flagString,
      keyString,
      varianceString + nameString + infoString + flagsExplanationString
    )
    /** String representation of symbol's definition.  It uses the
     *  symbol's raw info to avoid forcing types.
     */
    def defString = defStringCompose(signatureString)

    /** String representation of symbol's definition, using the supplied
     *  info rather than the symbol's.
     */
    def defStringSeenAs(info: Type) = defStringCompose(infoString(info))

    /** Concatenate strings separated by spaces */
    private def compose(ss: String*) = ss filter (_ != "") mkString " "

    def isSingletonExistential =
      nme.isSingletonName(name) && (info.bounds.hi.typeSymbol isSubClass SingletonClass)

    /** String representation of existentially bound variable */
    def existentialToString =
      if (isSingletonExistential && !settings.debug.value)
        "val " + nme.dropSingletonName(name) + ": " + dropSingletonType(info.bounds.hi)
      else defString
  }

  /** A class for term symbols */
  class TermSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TermName)
  extends Symbol(initOwner, initPos, initName) {
    private[this] var _referenced: Symbol = NoSymbol
    privateWithin = NoSymbol

    final override def isTerm = true
    override def name: TermName = rawname.toTermName
    override def companionSymbol: Symbol = companionClass
    override def moduleClass = if (isModule) referenced else NoSymbol

    override def hasDefault         = this hasFlag DEFAULTPARAM // overloaded with TRAIT
    override def isBridge           = this hasFlag BRIDGE
    override def isEarlyInitialized = this hasFlag PRESUPER
    override def isMethod           = this hasFlag METHOD
    override def isModule           = this hasFlag MODULE
    override def isOverloaded       = this hasFlag OVERLOADED
    override def isPackage          = this hasFlag PACKAGE
    override def isValueParameter   = this hasFlag PARAM

    override def isPackageObject  = isModule && (name == nme.PACKAGE)

    // The name in comments is what it is being disambiguated from.
    // TODO - rescue CAPTURED from BYNAMEPARAM so we can see all the names.
    override def resolveOverloadedFlag(flag: Long) = flag match {
      case DEFAULTPARAM => "<defaultparam>" // TRAIT
      case MIXEDIN      => "<mixedin>"      // EXISTENTIAL
      case LABEL        => "<label>"        // CONTRAVARIANT / INCONSTRUCTOR
      case PRESUPER     => "<presuper>"     // IMPLCLASS
      case BYNAMEPARAM  => if (this.isValueParameter) "<bynameparam>" else "<captured>" // COVARIANT
      case _            => super.resolveOverloadedFlag(flag)
    }

    def referenced: Symbol = _referenced
    def referenced_=(x: Symbol) { _referenced = x }

    def existentialBound = singletonBounds(this.tpe)

    def cloneSymbolImpl(owner: Symbol, newFlags: Long): Symbol =
      owner.newTermSymbol(name, pos, newFlags).copyAttrsFrom(this)

    def copyAttrsFrom(original: TermSymbol): this.type = {
      referenced = original.referenced
      this
    }

    private val validAliasFlags = SUPERACCESSOR | PARAMACCESSOR | MIXEDIN | SPECIALIZED

    override def alias: Symbol =
      if (hasFlag(validAliasFlags)) initialize.referenced
      else NoSymbol

    def setAlias(alias: Symbol): TermSymbol = {
      assert(alias != NoSymbol, this)
      assert(!alias.isOverloaded, alias)
      assert(hasFlag(validAliasFlags), this)

      referenced = alias
      this
    }

    override def outerSource: Symbol =
      if (name endsWith nme.OUTER) initialize.referenced
      else NoSymbol

    def setModuleClass(clazz: Symbol): TermSymbol = {
      assert(isModule, this)
      referenced = clazz
      this
    }

    def setLazyAccessor(sym: Symbol): TermSymbol = {
      assert(isLazy && (referenced == NoSymbol || referenced == sym), (this, debugFlagString, referenced, sym))
      referenced = sym
      this
    }

    override def lazyAccessor: Symbol = {
      assert(isLazy, this)
      referenced
    }

    /** change name by appending $$<fully-qualified-name-of-class `base`>
     *  Do the same for any accessed symbols or setters/getters
     */
    override def expandName(base: Symbol) {
      if (!hasFlag(EXPANDEDNAME)) {
        setFlag(EXPANDEDNAME)
        if (hasAccessorFlag && !isDeferred) {
          accessed.expandName(base)
        }
        else if (hasGetter) {
          getter(owner).expandName(base)
          setter(owner).expandName(base)
        }
        name = nme.expandedName(name.toTermName, base)
      }
    }

    protected def doCookJavaRawInfo() {
      def cook(sym: Symbol) {
        require(sym.isJavaDefined, sym)
        // @M: I think this is more desirable, but Martin prefers to leave raw-types as-is as much as possible
        // object rawToExistentialInJava extends TypeMap {
        //   def apply(tp: Type): Type = tp match {
        //     // any symbol that occurs in a java sig, not just java symbols
        //     // see http://lampsvn.epfl.ch/trac/scala/ticket/2454#comment:14
        //     case TypeRef(pre, sym, List()) if !sym.typeParams.isEmpty =>
        //       val eparams = typeParamsToExistentials(sym, sym.typeParams)
        //       existentialAbstraction(eparams, TypeRef(pre, sym, eparams map (_.tpe)))
        //     case _ =>
        //       mapOver(tp)
        //   }
        // }
        val tpe1 = rawToExistential(sym.tpe)
        // println("cooking: "+ sym +": "+ sym.tpe +" to "+ tpe1)
        if (tpe1 ne sym.tpe) {
          sym.setInfo(tpe1)
        }
      }

      if (isJavaDefined)
        cook(this)
      else if (isOverloaded)
        for (sym2 <- alternatives)
          if (sym2.isJavaDefined)
            cook(sym2)
    }
  }

  /** A class for module symbols */
  class ModuleSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TermName)
  extends TermSymbol(initOwner, initPos, initName) with DistinguishingFlag {
    def distinguishingFlag = MODULE

    private var flatname: TermName = null

    override def isModule = true
    override def moduleClass = referenced
    override def companionClass =
      flatOwnerInfo.decl(name.toTypeName).suchThat(_ isCoDefinedWith this)

    override def owner = (
      if (!isMethod && needsFlatClasses) rawowner.owner
      else rawowner
    )
    override def name: TermName = (
      if (!isMethod && needsFlatClasses) {
        if (flatname eq null)
          flatname = nme.flattenedName(rawowner.name, rawname)

        flatname
      }
      else rawname.toTermName
    )
  }

  class PackageSymbol protected[Symbols] (owner0: Symbol, pos0: Position, name0: TermName)
  extends ModuleSymbol(owner0, pos0, name0) with DistinguishingFlag {
    override def distinguishingFlag = super.distinguishingFlag | PACKAGE
    override def isPackage = true
  }

  /** A class for method symbols */
  class MethodSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TermName)
  extends TermSymbol(initOwner, initPos, initName) with DistinguishingFlag {
    def distinguishingFlag = METHOD
    // MethodSymbols pick up MODULE when trait-owned object accessors are cloned
    // during mixin composition.
    override protected def neverHasFlags = super.neverHasFlags & ~MODULE

    private[this] var mtpePeriod       = NoPeriod
    private[this] var mtpePre: Type    = _
    private[this] var mtpeResult: Type = _
    private[this] var mtpeInfo: Type   = _

    override def isMethod        = true
    override def isMacro         = this hasFlag MACRO
    override def isVarargsMethod = this hasFlag VARARGS
    override def isLiftedMethod  = this hasFlag LIFTED
    // TODO - this seems a strange definition for "isSourceMethod", given that
    // it does not make any specific effort to exclude synthetics.  Figure out what
    // this method is really for and what logic makes sense.
    override def isSourceMethod  = !(this hasFlag STABLE)  // exclude all accessors
    // unfortunately having the CASEACCESSOR flag does not actually mean you
    // are a case accessor (you can also be a field.)
    override def isCaseAccessorMethod = isCaseAccessor

    def typeAsMemberOf(pre: Type): Type = {
      if (mtpePeriod == currentPeriod) {
        if ((mtpePre eq pre) && (mtpeInfo eq info)) return mtpeResult
      } else if (isValid(mtpePeriod)) {
        mtpePeriod = currentPeriod
        if ((mtpePre eq pre) && (mtpeInfo eq info)) return mtpeResult
      }
      val res = pre.computeMemberType(this)
      mtpePeriod = currentPeriod
      mtpePre = pre
      mtpeInfo = info
      mtpeResult = res
      res
    }
  }

  class AliasTypeSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TypeName)
  extends TypeSymbol(initOwner, initPos, initName) {
    final override def isAliasType = true
  }

  class AbstractTypeSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TypeName)
  extends TypeSymbol(initOwner, initPos, initName) {
    final override def isAbstractType = true
    override def existentialBound = this.info
  }

  /** A class of type symbols. Alias and abstract types are direct instances
   *  of this class. Classes are instances of a subclass.
   */
  abstract class TypeSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TypeName)
  extends Symbol(initOwner, initPos, initName) {
    privateWithin = NoSymbol

    final override def isType   = true
    override def isNonClassType = true
    
    override def resolveOverloadedFlag(flag: Long) = flag match {
      case TRAIT         => "<trait>"         // DEFAULTPARAM
      case EXISTENTIAL   => "<existential>"   // MIXEDIN
      case COVARIANT     => "<covariant>"     // BYNAMEPARAM / CAPTURED
      case CONTRAVARIANT => "<contravariant>" // LABEL / INCONSTRUCTOR (overridden again in ClassSymbol)
      case _             => super.resolveOverloadedFlag(flag)
    }

    private var tyconCache: Type = null
    private var tyconRunId = NoRunId
    private var tpeCache: Type = _
    private var tpePeriod = NoPeriod

    override def isAbstractType          = this hasFlag DEFERRED
    override def isContravariant         = this hasFlag CONTRAVARIANT
    override def isCovariant             = this hasFlag COVARIANT
    override def isExistentialQuantified = isExistentiallyBound && !isSkolem
    override def isExistentiallyBound    = this hasFlag EXISTENTIAL
    override def isTypeParameter         = isTypeParameterOrSkolem && !isSkolem
    override def isTypeParameterOrSkolem = this hasFlag PARAM

    /** Overridden in subclasses for which it makes sense.
     */
    def existentialBound: Type = abort("unexpected type: "+this.getClass+ " "+debugLocationString)

    // a type symbol bound by an existential type, for instance the T in
    // List[T] forSome { type T }

    override def name: TypeName = super.name.toTypeName
    private def newPrefix = if (this hasFlag EXISTENTIAL | PARAM) NoPrefix else owner.thisType
    private def newTypeRef(targs: List[Type]) = typeRef(newPrefix, this, targs)

    /** Let's say you have a type definition
     *
     *  {{{
     *    type T <: Number
     *  }}}
     *
     *  and tsym is the symbol corresponding to T. Then
     *
     *  {{{
     *    tsym.info = TypeBounds(Nothing, Number)
     *    tsym.tpe  = TypeRef(NoPrefix, T, List())
     *  }}}
     */
    override def tpe: Type = {
      if (tpeCache eq NoType) throw CyclicReference(this, typeConstructor)
      if (tpePeriod != currentPeriod) {
        if (isValid(tpePeriod)) {
          tpePeriod = currentPeriod
        } else {
          if (isInitialized) tpePeriod = currentPeriod
          tpeCache = NoType
          val targs =
            if (phase.erasedTypes && this != ArrayClass) List()
            else unsafeTypeParams map (_.typeConstructor)
            //@M! use typeConstructor to generate dummy type arguments,
            // sym.tpe should not be called on a symbol that's supposed to be a higher-kinded type
            // memberType should be used instead, that's why it uses tpeHK and not tpe
          tpeCache = newTypeRef(targs)
        }
      }
      assert(tpeCache ne null/*, "" + this + " " + phase*/)//debug
      tpeCache
    }

    /** @M -- tpe vs tpeHK:
     *
     *    tpe: creates a TypeRef with dummy type arguments and kind *
     *  tpeHK: creates a TypeRef with no type arguments but with type parameters
     *
     * If typeParams is nonEmpty, calling tpe may hide errors or
     * introduce spurious ones. (For example, when deriving a type from
     * the symbol of a type argument that may be higher-kinded.) As far
     * as I can tell, it only makes sense to call tpe in conjunction
     * with a substitution that replaces the generated dummy type
     * arguments by their actual types.
     *
     * TODO: the above conditions desperately need to be enforced by code.
     */
    override def tpeHK = typeConstructor // @M! used in memberType

    override def typeConstructor: Type = {
      if ((tyconCache eq null) || tyconRunId != currentRunId) {
        tyconCache = newTypeRef(Nil)
        tyconRunId = currentRunId
      }
      assert(tyconCache ne null)
      tyconCache
    }

    override def info_=(tp: Type) {
      tpePeriod = NoPeriod
      tyconCache = null
      super.info_=(tp)
    }

    final override def isNonBottomSubClass(that: Symbol): Boolean = (
      (this eq that) || this.isError || that.isError ||
      info.baseTypeIndex(that) >= 0
    )

    override def reset(completer: Type) {
      super.reset(completer)
      tpePeriod = NoPeriod
      tyconRunId = NoRunId
    }

    /*** example:
     * public class Test3<T> {}
     * public class Test1<T extends Test3> {}
     * info for T in Test1 should be >: Nothing <: Test3[_]
     */
    protected def doCookJavaRawInfo() {
      if (isJavaDefined || owner.isJavaDefined) {
        val tpe1 = rawToExistential(info)
        // println("cooking type: "+ this +": "+ info +" to "+ tpe1)
        if (tpe1 ne info) {
          setInfo(tpe1)
        }
      }
    }

    /** Default implementation. */
    def cloneSymbolImpl(owner: Symbol, newFlags: Long): Symbol =
      owner.newNonClassSymbol(name, pos, newFlags)

    incCounter(typeSymbolCount)
  }

  /** A class for type parameters viewed from inside their scopes
   *
   *  @param origin  Can be either a tree, or a symbol, or null.
   *  If skolem got created from newTypeSkolem (called in Namers), origin denotes
   *  the type parameter from which the skolem was created. If it got created from
   *  skolemizeExistential, origin is either null or a Tree. If it is a Tree, it indicates
   *  where the skolem was introduced (this is important for knowing when to pack it
   *  again into ab Existential). origin is `null` only in skolemizeExistentials called
   *  from <:< or isAsSpecific, because here its value does not matter.
   *  I believe the following invariant holds:
   *
   *     origin.isInstanceOf[Symbol] == !hasFlag(EXISTENTIAL)
   */
  class TypeSkolem protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TypeName, origin: AnyRef)
  extends TypeSymbol(initOwner, initPos, initName) {
    /** The skolemization level in place when the skolem was constructed */
    val level = skolemizationLevel

    final override def isSkolem = true

    override def isGADTSkolem        = this hasFlag CASEACCESSOR | SYNTHETIC
    override def isTypeSkolem        = this hasFlag PARAM
    override def isExistentialSkolem = this hasFlag EXISTENTIAL
    override def isAbstractType      = this hasFlag DEFERRED

    override def isExistentialQuantified = false
    override def existentialBound = if (isAbstractType) this.info else super.existentialBound

    /** If typeskolem comes from a type parameter, that parameter, otherwise skolem itself */
    override def deSkolemize = origin match {
      case s: Symbol => s
      case _ => this
    }

    /** If type skolem comes from an existential, the tree where it was created */
    override def unpackLocation = origin

    //@M! (not deSkolemize.typeParams!!), also can't leave superclass definition: use info, not rawInfo
    override def typeParams = info.typeParams

    override def cloneSymbolImpl(owner: Symbol, newFlags: Long): Symbol =
      owner.newTypeSkolemSymbol(name, origin, pos, newFlags)

    override def nameString: String =
      if (settings.debug.value) (super.nameString + "&" + level)
      else super.nameString
  }

  /** A class for class symbols */
  class ClassSymbol protected[Symbols] (initOwner: Symbol, initPos: Position, initName: TypeName)
  extends TypeSymbol(initOwner, initPos, initName) {
    private[this] var flatname: TypeName       = _
    private[this] var source: AbstractFileType = _
    private[this] var thissym: Symbol          = this

    private[this] var thisTypeCache: Type      = _
    private[this] var thisTypePeriod           = NoPeriod
    private[this] var typeOfThisCache: Type    = _
    private[this] var typeOfThisPeriod         = NoPeriod

    override protected def alwaysHasFlags: Long = 0L
    override protected def neverHasFlags: Long = 0L

    override def resolveOverloadedFlag(flag: Long) = flag match {
      case INCONSTRUCTOR => "<inconstructor>" // CONTRAVARIANT / LABEL
      case EXISTENTIAL   => "<existential>"   // MIXEDIN
      case _             => super.resolveOverloadedFlag(flag)
    }

    final override def isClass = true
    final override def isNonClassType = false
    final override def isAbstractType = false
    final override def isAliasType = false

    override def isAbstractClass           = this hasFlag ABSTRACT
    override def isClassLocalToConstructor = this hasFlag INCONSTRUCTOR
    override def isImplClass               = this hasFlag IMPLCLASS
    override def isModuleClass             = this hasFlag MODULE
    override def isPackageClass            = this hasFlag PACKAGE
    override def isTrait                   = this hasFlag TRAIT

    override def isConcreteClass      = !(this hasFlag ABSTRACT | TRAIT)
    override def isPackageObjectClass = isModuleClass && (name == tpnme.PACKAGE)

    def isTraitOrImplClass   = this hasFlag TRAIT | IMPLCLASS
    def isNonImplModuleClass = isModuleClass && !isImplClass

    override def isAnonymousClass        = name containsName tpnme.ANON_CLASS_NAME
    override def isAnonOrRefinementClass = isAnonymousClass || isRefinementClass
    override def isNestedClass           = !owner.isPackageClass
    override def isNumericValueClass     = definitions.isNumericValueClass(this)
    override def isPrimitiveValueClass   = definitions.isPrimitiveValueClass(this)

    /** Is this class locally defined?
     *  A class is local, if
     *   - it is anonymous, or
     *   - its owner is a value
     *   - it is defined within a local class
     */
    override def isLocalClass = (
         isAnonOrRefinementClass
      || isLocal
      || !owner.isPackageClass && owner.isLocalClass
    )
    override def isStableClass = (this hasFlag STABLE) || checkStable()

    private def checkStable() = {
      def hasNoAbstractTypeMember(clazz: Symbol): Boolean =
        (clazz hasFlag STABLE) || {
          var e = clazz.info.decls.elems
          while ((e ne null) && !(e.sym.isAbstractType && info.member(e.sym.name) == e.sym))
            e = e.next
          e == null
        }
      (info.baseClasses forall hasNoAbstractTypeMember) && {
        setFlag(STABLE)
        true
      }
    }

    override def enclClassChain = this :: owner.enclClassChain

    /** A helper method that factors the common code used the discover a
     *  companion module of a class. If a companion module exists, its symbol is
     *  returned, otherwise, `NoSymbol` is returned.
     */
    protected final def companionModule0: Symbol =
      flatOwnerInfo.decl(name.toTermName).suchThat(
        sym => sym.hasFlag(MODULE) && (sym isCoDefinedWith this) && !sym.isMethod)

    override def companionModule    = companionModule0
    override def companionSymbol    = companionModule0
    override def linkedClassOfClass = companionModule.moduleClass

    override def existentialBound = GenPolyType(this.typeParams, TypeBounds.upper(this.classBound))

    def primaryConstructorName = if (isTraitOrImplClass) nme.MIXIN_CONSTRUCTOR else nme.CONSTRUCTOR

    override def primaryConstructor = {
      val c = info decl primaryConstructorName
      if (c.isOverloaded) c.alternatives.head else c
    }

    override def sourceFile =
      if (owner.isPackageClass) source
      else super.sourceFile
    override def sourceFile_=(f: AbstractFileType) { source = f }

    override def reset(completer: Type) {
      super.reset(completer)
      thissym = this
    }

    /** the type this.type in this class */
    override def thisType: Type = {
      val period = thisTypePeriod
      if (period != currentPeriod) {
        thisTypePeriod = currentPeriod
        if (!isValid(period)) thisTypeCache = ThisType(this)
      }
      thisTypeCache
    }

    /** the self type of an object foo is foo.type, not class<foo>.this.type
     */
    override def typeOfThis: Type = {
      if (isNonImplModuleClass && owner != NoSymbol) {
        val period = typeOfThisPeriod
        if (period != currentPeriod) {
          typeOfThisPeriod = currentPeriod
          if (!isValid(period))
            typeOfThisCache = singleType(owner.thisType, sourceModule)
        }
        typeOfThisCache
      }
      else thisSym.tpe
    }

    override def owner: Symbol =
      if (needsFlatClasses) rawowner.owner else rawowner

    override def name: TypeName = (
      if (needsFlatClasses) {
        if (flatname eq null)
          flatname = nme.flattenedName(rawowner.name, rawname).toTypeName

        flatname
      }
      else rawname.toTypeName
    )

    /** A symbol carrying the self type of the class as its type */
    override def thisSym: Symbol = thissym

    /** Sets the self type of the class */
    override def typeOfThis_=(tp: Type) {
      thissym = newThisSym(pos).setInfo(tp)
    }

    override def cloneSymbolImpl(owner: Symbol, newFlags: Long): ClassSymbol = {
      val clone = owner.newClassSymbol(name, pos, newFlags)
      if (thisSym != this) {
        clone.typeOfThis = typeOfThis
        clone.thisSym.name = thisSym.name
      }
      clone
    }

    // Must have this line.
    override def sourceModule = if (isModuleClass) companionModule else NoSymbol

    override def firstParamAccessor =
      info.decls.find(_ hasAllFlags PARAMACCESSOR | METHOD) getOrElse NoSymbol

    private[this] var childSet: Set[Symbol] = Set()
    override def children = childSet
    override def addChild(sym: Symbol) { childSet = childSet + sym }

    incCounter(classSymbolCount)
  }

  /** A class for module class symbols
   *  Note: Not all module classes are of this type; when unpickled, we get
   *  plain class symbols!
   */
  class ModuleClassSymbol protected[Symbols] (owner: Symbol, pos: Position, name: TypeName)
  extends ClassSymbol(owner, pos, name) with DistinguishingFlag {
    def distinguishingFlag = MODULE

    private var module: Symbol = null
    private var implicitMembersCacheValue: List[Symbol] = Nil
    private var implicitMembersCacheKey1: Type = NoType
    private var implicitMembersCacheKey2: ScopeEntry = null

    override def isModuleClass = true
    override def linkedClassOfClass = companionClass

    def implicitMembers: List[Symbol] = {
      val tp = info
      if ((implicitMembersCacheKey1 ne tp) || (implicitMembersCacheKey2 ne tp.decls.elems)) {
        // Skip a package object class, because the members are also in
        // the package and we wish to avoid spurious ambiguities as in pos/t3999.
        if (!isPackageObjectClass) {
          implicitMembersCacheKey1 = tp
          implicitMembersCacheKey2 = tp.decls.elems
          implicitMembersCacheValue = tp.implicitMembers
        }
      }
      implicitMembersCacheValue
    }
    override def sourceModule = module
    override def sourceModule_=(module: Symbol) { this.module = module }
  }

  class PackageClassSymbol protected[Symbols] (owner0: Symbol, pos0: Position, name0: TypeName)
  extends ModuleClassSymbol(owner0, pos0, name0) with DistinguishingFlag {
    override def distinguishingFlag = super.distinguishingFlag | PACKAGE
    override def sourceModule = companionModule
    override def enclClassChain = Nil
    override def isPackageClass = true
  }

  class RefinementClassSymbol protected[Symbols] (owner0: Symbol, pos0: Position)
  extends ClassSymbol(owner0, pos0, tpnme.REFINE_CLASS_NAME) {
    override def name_=(name: Name) {
      assert(false, "Cannot set name of RefinementClassSymbol to " + name)
      super.name_=(name)
    }
    override def isRefinementClass       = true
    override def isAnonOrRefinementClass = true
    override def isLocalClass            = true
    override def hasMeaninglessName      = true
    override def companionModule: Symbol = NoSymbol

    /** The mentioned twist.  A refinement class has transowner X
     *  if any of its parents has transowner X.
     */
    override def hasTransOwner(sym: Symbol) = (
         super.hasTransOwner(sym)
      || info.parents.exists(_.typeSymbol hasTransOwner sym)
    )
  }

  class FreeVar(name0: TermName, val value: Any) extends TermSymbol(NoSymbol, NoPosition, name0) {
    override def hashCode = if (value == null) 0 else value.hashCode
    override def equals(other: Any): Boolean = other match {
      case that: FreeVar => this.value.asInstanceOf[AnyRef] eq that.value.asInstanceOf[AnyRef]
      case _             => false
    }
  }

  /** An object representing a missing symbol */
  class NoSymbol protected[Symbols]() extends Symbol(null, NoPosition, nme.NO_NAME) {
    synchronized {
      setInfo(NoType)
      privateWithin = this
    }
    override def info_=(info: Type) = {
      infos = TypeHistory(1, NoType, null)
      unlock()
      validTo = currentPeriod
    }
    override def flagMask = AllFlags
    override def exists = false
    override def isHigherOrderTypeParameter = false
    override def companionClass = NoSymbol
    override def companionModule = NoSymbol
    override def companionSymbol = NoSymbol
    override def isSubClass(that: Symbol) = false
    override def filter(cond: Symbol => Boolean) = this
    override def defString: String = toString
    override def locationString: String = ""
    override def enclClassChain = Nil
    override def enclClass: Symbol = this
    override def enclosingTopLevelClass: Symbol = this
    override def enclosingPackageClass: Symbol = this
    override def enclMethod: Symbol = this
    override def sourceFile: AbstractFileType = null
    override def ownerChain: List[Symbol] = List()
    override def ownersIterator: Iterator[Symbol] = Iterator.empty
    override def alternatives: List[Symbol] = List()
    override def reset(completer: Type) {}
    override def info: Type = NoType
    override def existentialBound: Type = NoType
    override def rawInfo: Type = NoType
    protected def doCookJavaRawInfo() {}
    override def accessBoundary(base: Symbol): Symbol = RootClass
    def cloneSymbolImpl(owner: Symbol, newFlags: Long): Symbol = abort("NoSymbol.clone()")
    override def originalEnclosingMethod = this

    override def owner: Symbol =
      abort("no-symbol does not have an owner (this is a bug: scala " + scala.util.Properties.versionString + ")")
    override def typeConstructor: Type =
      abort("no-symbol does not have a type constructor (this may indicate scalac cannot find fundamental classes)")
  }

  protected def makeNoSymbol: NoSymbol = new NoSymbol

  lazy val NoSymbol: NoSymbol = makeNoSymbol

  /** Derives a new list of symbols from the given list by mapping the given
   *  list across the given function.  Then fixes the info of all the new symbols
   *  by substituting the new symbols for the original symbols.
   *
   *  @param    syms    the prototypical symbols
   *  @param    symFn   the function to create new symbols
   *  @return           the new list of info-adjusted symbols
   */
  def deriveSymbols(syms: List[Symbol], symFn: Symbol => Symbol): List[Symbol] = {
    val syms1 = syms map symFn
    syms1 map (_ substInfo (syms, syms1))
  }

  /** Derives a new Type by first deriving new symbols as in deriveSymbols,
   *  then performing the same oldSyms => newSyms substitution on `tpe` as is
   *  performed on the symbol infos in deriveSymbols.
   *
   *  @param    syms    the prototypical symbols
   *  @param    symFn   the function to create new symbols
   *  @param    tpe     the prototypical type
   *  @return           the new symbol-subsituted type
   */
  def deriveType(syms: List[Symbol], symFn: Symbol => Symbol)(tpe: Type): Type = {
    val syms1 = deriveSymbols(syms, symFn)
    tpe.substSym(syms, syms1)
  }
  /** Derives a new Type by instantiating the given list of symbols as
   *  WildcardTypes.
   *
   *  @param    syms    the symbols to replace
   *  @return           the new type with WildcardType replacing those syms
   */
  def deriveTypeWithWildcards(syms: List[Symbol])(tpe: Type): Type = {
    if (syms.isEmpty) tpe
    else tpe.instantiateTypeParams(syms, syms map (_ => WildcardType))
  }
  /** Convenience functions which derive symbols by cloning.
   */
  def cloneSymbols(syms: List[Symbol]): List[Symbol] =
    deriveSymbols(syms, _.cloneSymbol)
  def cloneSymbolsAtOwner(syms: List[Symbol], owner: Symbol): List[Symbol] =
    deriveSymbols(syms, _ cloneSymbol owner)

  /** Clone symbols and apply the given function to each new symbol's info.
   *
   *  @param    syms    the prototypical symbols
   *  @param    infoFn  the function to apply to the infos
   *  @return           the newly created, info-adjusted symbols
   */
  def cloneSymbolsAndModify(syms: List[Symbol], infoFn: Type => Type): List[Symbol] =
    cloneSymbols(syms) map (_ modifyInfo infoFn)
  def cloneSymbolsAtOwnerAndModify(syms: List[Symbol], owner: Symbol, infoFn: Type => Type): List[Symbol] =
    cloneSymbolsAtOwner(syms, owner) map (_ modifyInfo infoFn)

  /** Functions which perform the standard clone/substituting on the given symbols and type,
   *  then call the creator function with the new symbols and type as arguments.
   */
  def createFromClonedSymbols[T](syms: List[Symbol], tpe: Type)(creator: (List[Symbol], Type) => T): T = {
    val syms1 = cloneSymbols(syms)
    creator(syms1, tpe.substSym(syms, syms1))
  }
  def createFromClonedSymbolsAtOwner[T](syms: List[Symbol], owner: Symbol, tpe: Type)(creator: (List[Symbol], Type) => T): T = {
    val syms1 = cloneSymbolsAtOwner(syms, owner)
    creator(syms1, tpe.substSym(syms, syms1))
  }

  /** A deep map on a symbol's paramss.
   */
  def mapParamss[T](sym: Symbol)(f: Symbol => T): List[List[T]] = mmap(sym.info.paramss)(f)

  /** An exception for cyclic references of symbol definitions */
  case class CyclicReference(sym: Symbol, info: Type)
  extends TypeError("illegal cyclic reference involving " + sym) {
    if (settings.debug.value) printStackTrace()
  }

  case class InvalidCompanions(sym1: Symbol, sym2: Symbol) extends Throwable(
    "Companions '" + sym1 + "' and '" + sym2 + "' must be defined in same file:\n" +
    "  Found in " + sym1.sourceFile.canonicalPath + " and " + sym2.sourceFile.canonicalPath
  ) {
      override def toString = getMessage
  }

  /** A class for type histories */
  private sealed case class TypeHistory(var validFrom: Period, info: Type, prev: TypeHistory) {
    assert((prev eq null) || phaseId(validFrom) > phaseId(prev.validFrom), this)
    assert(validFrom != NoPeriod, this)

    override def toString() =
      "TypeHistory(" + phaseOf(validFrom)+":"+runId(validFrom) + "," + info + "," + prev + ")"
    
    def toList: List[TypeHistory] = this :: ( if (prev eq null) Nil else prev.toList )
  }
}
