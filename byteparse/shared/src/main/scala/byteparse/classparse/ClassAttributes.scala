package byteparse
package classparse

import fastparse.allByte._

object ClassAttributes {
  import ClassParser.Info._
  import ClassParser.BasicElems._
  import ClassParser.Ast._
  import ClassParser._
  import CodeParser._

  sealed abstract class Attribute

  case class BasicAttribute(name: String, info: Array[Byte]) extends Attribute {
    override def equals(other: Any): Boolean =
      other match {
        case attr: BasicAttribute => name == attr.name && info.deep == attr.info.deep
        case _ => false
      }

    override def toString = {
      import fastparse.ElemTypeFormatter.ByteFormatter
      s"BasicAttribute($name,${ByteFormatter.prettyPrint(info)})"
    }
  }

  case class ConstantValueAttribute(value: BasicElem) extends Attribute

  case class ExceptionHandler(startIdx: Int, endIdx: Int, handlerIdx: Int, catchType: Option[Class])

  case class CodeAttribute(maxStack: Int, maxLocals: Int,
                           code: Seq[OpCode], exceptions: Seq[ExceptionHandler],
                           attributes: Seq[Attribute]) extends Attribute

  case class ExceptionsAttribute(exceptions: Seq[Class]) extends Attribute


  case class InnerClass(innerClass: Class, outerClass: Option[Class],
                        innerName: Option[String], accessFlags: InnerClassFlags)

  case class InnerClassesAttribute(classes: Seq[InnerClass]) extends Attribute

  case class EnclosingMethodAttribute(enclosingClass: Class, enclosingMethodName: Option[String],
                                      enclosingMethodDescriptor: Option[String]) extends Attribute

  object SyntheticAttribute extends Attribute

  case class SignatureAttribute(signature: String) extends Attribute

  case class SourceFileAttribute(sourceFile: String) extends Attribute

  object DeprecatedAttribute extends Attribute

  case class BootstrapMethod(bootstrapMethodRef: MethodHandle, bootstrapArguments: Seq[PoolItem])

  case class BootstrapMethodsAttribute(bootstrapMethods: Seq[BootstrapMethod]) extends Attribute

  import ByteUtils.BE._

  val constantValue = P( AnyWordI ).map(idx => (classInfo: ClassFileInfo) =>
    ConstantValueAttribute(
      classInfo.getInfoByIndex[PoolInfo](idx).get match {
        case BasicElemInfo(elem) => elem
        case StringInfo(idx) => StringElem(classInfo.getStringByIndex(idx))
      }
    ))

  val code = {
    val exceptionHandler = {
      val start_pc = AnyWordI
      val end_pc = AnyWordI
      val handler_pc = AnyWordI
      val catch_type = AnyWordI
      P( start_pc ~ end_pc ~ handler_pc ~ catch_type ).map {
        case (spc: Int, epc: Int, hpc: Int, ctype: Int) =>
          (classInfo: ClassFileInfo) => ExceptionHandler(
            spc,
            epc,
            hpc,
            if (ctype == 0) None
            else Some(Class(classInfo, classInfo.getInfoByIndex[ClassInfo](ctype).get)))
      }
    }

    val max_stack = AnyWordI
    val max_locals = AnyWordI
    val code = AnyDwordI.flatMap(l => AnyByte.rep(exactly=l).!).map(parseCode)
    val exception_table = repeatWithSize(exceptionHandler)
    val attributes = repeatWithSize(attributeInfo)

    P( max_stack ~ max_locals ~ code ~ exception_table ~ attributes ).map {
       case (maxs: Int, maxl: Int, code: Seq[OpCode], exceptions, attrs: Seq[AttributeInfo]) =>
         (classInfo: ClassFileInfo) => CodeAttribute(
           maxs,
           maxl,
           code,
           exceptions.map(_(classInfo)),
           attrs.map(attr => convertToAttribute(classInfo, attr))
         )
     }
  }

  val innerClasses = {
    val innerClass = {
      val inner_class_info_index = AnyWordI
      val outer_class_info_index = AnyWordI
      val inner_name_index = AnyWordI
      val inner_class_access_flags = AnyWord.!

      P( inner_class_info_index ~ outer_class_info_index ~
         inner_name_index ~ inner_class_access_flags ).map {
        case (inIdx: Int, outIdx: Int, inName: Int, inFlags: Array[Byte]) =>
          (classInfo: ClassFileInfo) => InnerClass(
            Class(classInfo, classInfo.getInfoByIndex[ClassInfo](inIdx).get),
            if (outIdx == 0) None
            else Some(Class(classInfo, classInfo.getInfoByIndex[ClassInfo](outIdx).get)),
            if (inName == 0) None
            else Some(classInfo.getStringByIndex(inName)),
            InnerClassFlags(inFlags)
          )
      }
    }

    repeatWithSize(innerClass).map(
      ics => (classInfo: ClassFileInfo) => InnerClassesAttribute(ics.map(_(classInfo))))
  }

  val exceptions =
    repeatWithSize(AnyWordI).map(exceptionsIdxs =>
      (classInfo: ClassFileInfo) => ExceptionsAttribute(
        exceptionsIdxs.map(idx => Class(classInfo, classInfo.getInfoByIndex[ClassInfo](idx).get))
      ))

  val enclosingMethod = {
    val class_index = AnyWordI
    val method_index = AnyWordI

    P( class_index ~ method_index ).map {
      case (classIdx: Int, methodIdx: Int) =>
        (classInfo: ClassFileInfo) => {
          val (name, descriptor) =
            if (methodIdx == 0) (None, None)
            else classInfo.getInfoByIndex[NameAndTypeInfo](methodIdx).get match {
              case NameAndTypeInfo(nameIdx, descriptorIdx) =>
                (Some(classInfo.getStringByIndex(nameIdx)), Some(classInfo.getStringByIndex(descriptorIdx)))
            }

          EnclosingMethodAttribute(
            Class(classInfo, classInfo.getInfoByIndex[ClassInfo](classIdx).get),
            name,
            descriptor
          )
        }
    }
  }

  val bootstrapMethods = {
    val bootstrap_method_ref = AnyWordI
    val bootstrap_arguments = repeatWithSize(AnyWordI)

    val bootstrapMethod =
      P( bootstrap_method_ref ~ bootstrap_arguments ).map {
        case (refIdx: Int, argsIdxs: Seq[Int]) => (classInfo: ClassFileInfo) =>
          BootstrapMethod(
            MethodHandle(classInfo, classInfo.getInfoByIndex[MethodHandleInfo](refIdx).get),
            argsIdxs.map(
              argIdx => convertToPoolItem(classInfo, classInfo.getInfoByIndex[PoolInfo](argIdx).get))
          )
      }

    repeatWithSize(bootstrapMethod).map(
      bms => (classInfo: ClassFileInfo) => BootstrapMethodsAttribute(bms.map(_(classInfo))))
  }

  val attributeParsers = Map[String, Parser[(ClassFileInfo) => Attribute]](
    "ConstantValue" -> constantValue,
    "Code" -> code,
    "Exceptions" -> exceptions,
    "InnerClasses" -> innerClasses,
    "EnclosingMethod" -> enclosingMethod,
    "Synthetic" -> PassWith((classInfo: ClassFileInfo) => SyntheticAttribute),
    "Signature" ->
      P( AnyWordI ).map(idx => (classInfo: ClassFileInfo) =>
        SignatureAttribute(classInfo.getStringByIndex(idx))),
    "SourceFile" ->
      P( AnyWordI ).map(idx => (classInfo: ClassFileInfo) =>
        SourceFileAttribute(classInfo.getStringByIndex(idx))),
    "Deprecated" -> PassWith((classInfo: ClassFileInfo) => DeprecatedAttribute),
    "BootstrapMethods" -> bootstrapMethods
  )

  def convertToAttribute(classInfo: Info.ClassFileInfo, attribute: AttributeInfo): Attribute = {
    val attrName = classInfo.getStringByIndex(attribute.nameIndex)
    if (attributeParsers.contains(attrName)) {
      val Parsed.Success(attr, _) = attributeParsers(attrName).parse(attribute.info)
      attr(classInfo)
    } else {
      BasicAttribute(attrName, attribute.info)
    }
  }

}
