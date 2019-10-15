package bloop.config

import java.nio.file.{Files, Path, Paths}

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._

import scala.util.Try
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import scala.util.Failure
import scala.util.Success

object ConfigCodecs {

  implicit val codecPath: JsonValueCodec[Path] = new JsonValueCodec[Path] {
    val nullValue: Path = Paths.get("")
    def encodeValue(x: Path, out: JsonWriter): Unit = out.writeVal(x.toString)
    def decodeValue(in: JsonReader, default: Path): Path =
      if (in.isNextToken('"')) {
        in.rollbackToken()
        Try(Paths.get(in.readString(""))).toOption.getOrElse(nullValue)
      } else {
        in.rollbackToken()
        nullValue
      }
  }

  implicit val codecCompileOrder: JsonValueCodec[Config.CompileOrder] = {
    new JsonValueCodec[Config.CompileOrder] {
      val nullValue: Config.CompileOrder = null.asInstanceOf[Config.CompileOrder]
      def encodeValue(x: Config.CompileOrder, out: JsonWriter): Unit =
        x match {
          case Config.Mixed => out.writeVal(Config.Mixed.id)
          case Config.JavaThenScala => out.writeVal(Config.JavaThenScala.id)
          case Config.ScalaThenJava => out.writeVal(Config.ScalaThenJava.id)
        }

      def decodeValue(in: JsonReader, default: Config.CompileOrder): Config.CompileOrder =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.Mixed.id => Config.Mixed
            case Config.JavaThenScala.id => Config.JavaThenScala
            case Config.ScalaThenJava.id => Config.ScalaThenJava
            case _ =>
              in.decodeError(
                s"Expected compile order ${Config.CompileOrder.All.mkString("'", "', '", "'")}"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecLinkerMode: JsonValueCodec[Config.LinkerMode] = {
    new JsonValueCodec[Config.LinkerMode] {
      val nullValue: Config.LinkerMode = null.asInstanceOf[Config.LinkerMode]
      def encodeValue(x: Config.LinkerMode, out: JsonWriter): Unit = {
        val str = x match {
          case Config.LinkerMode.Debug => Config.LinkerMode.Debug.id
          case Config.LinkerMode.Release => Config.LinkerMode.Release.id
        }
        out.writeVal(str)
      }
      def decodeValue(in: JsonReader, default: Config.LinkerMode): Config.LinkerMode =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.LinkerMode.Debug.id => Config.LinkerMode.Debug
            case Config.LinkerMode.Release.id => Config.LinkerMode.Release
            case _ =>
              in.decodeError(
                s"Expected linker mode ${Config.LinkerMode.All.mkString("'", "', '", "'")}"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecModuleKindJS: JsonValueCodec[Config.ModuleKindJS] = {
    new JsonValueCodec[Config.ModuleKindJS] {
      val nullValue: Config.ModuleKindJS = null.asInstanceOf[Config.ModuleKindJS]
      def encodeValue(x: Config.ModuleKindJS, out: JsonWriter): Unit = {
        val str = x match {
          case Config.ModuleKindJS.CommonJSModule => Config.ModuleKindJS.CommonJSModule.id
          case Config.ModuleKindJS.NoModule => Config.ModuleKindJS.NoModule.id
        }
        out.writeVal(str)
      }
      def decodeValue(in: JsonReader, default: Config.ModuleKindJS): Config.ModuleKindJS =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.ModuleKindJS.CommonJSModule.id => Config.ModuleKindJS.CommonJSModule
            case Config.ModuleKindJS.NoModule.id => Config.ModuleKindJS.NoModule
            case _ =>
              in.decodeError(
                s"Expected linker mode ${Config.ModuleKindJS.All.mkString("'", "', '", "'")}"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecJvmConfig: JsonValueCodec[Config.JvmConfig] =
    JsonCodecMaker.make[Config.JvmConfig](CodecMakerConfig)

  implicit val codecJsConfig: JsonValueCodec[Config.JsConfig] =
    JsonCodecMaker.make[Config.JsConfig](CodecMakerConfig)

  implicit val codecNativeConfig: JsonValueCodec[Config.NativeConfig] =
    JsonCodecMaker.make[Config.NativeConfig](CodecMakerConfig)

  private case class MainClass(mainClass: Option[String])
  private implicit val codecMainClass: JsonValueCodec[MainClass] = {
    new JsonValueCodec[MainClass] {
      val nullValue: MainClass = null.asInstanceOf[MainClass]
      val codecOption: JsonValueCodec[Option[String]] =
        JsonCodecMaker.make[Option[String]](CodecMakerConfig)
      val codecList: JsonValueCodec[List[String]] =
        JsonCodecMaker.make[List[String]](CodecMakerConfig)
      def encodeValue(x: MainClass, out: JsonWriter): Unit = {
        codecOption.encodeValue(x.mainClass, out)
      }

      def decodeValue(in: JsonReader, default: MainClass): MainClass = {
        if (in.isNextToken('[')) {
          in.rollbackToken()
          codecList.decodeValue(in, Nil) match {
            case Nil => MainClass(None)
            case List(mainClass) => MainClass(Some(mainClass))
            case mainClasses =>
              in.decodeError(s"Expected only one main class, obtained $mainClasses!")
          }
        } else {
          in.rollbackToken()
          MainClass(codecOption.decodeValue(in, None))
        }
      }
    }
  }

  private sealed trait JsoniterPlatform
  private case class jvm(config: Config.JvmConfig, mainClass: MainClass) extends JsoniterPlatform
  private case class js(config: Config.JsConfig, mainClass: MainClass) extends JsoniterPlatform
  private case class native(config: Config.NativeConfig, mainClass: MainClass)
      extends JsoniterPlatform

  implicit val codecPlatform: JsonValueCodec[Config.Platform] =
    new JsonValueCodec[Config.Platform] {
      val codec: JsonValueCodec[JsoniterPlatform] =
        JsonCodecMaker.make[JsoniterPlatform](
          CodecMakerConfig.withDiscriminatorFieldName(Some("name"))
        )
      val nullValue: Config.Platform = null.asInstanceOf[Config.Platform]
      def encodeValue(x: Config.Platform, out: JsonWriter): Unit = {
        codec.encodeValue(
          x match {
            case Config.Platform.Jvm(config, mainClass) => jvm(config, MainClass(mainClass))
            case Config.Platform.Js(config, mainClass) => js(config, MainClass(mainClass))
            case Config.Platform.Native(config, mainClass) => native(config, MainClass(mainClass))
          },
          out
        )
      }
      def decodeValue(in: JsonReader, default: Config.Platform): Config.Platform = {
        codec.decodeValue(in, null) match {
          case jvm(config, mainClass) => Config.Platform.Jvm(config, mainClass.mainClass)
          case js(config, mainClass) => Config.Platform.Js(config, mainClass.mainClass)
          case native(config, mainClass) => Config.Platform.Native(config, mainClass.mainClass)
        }
      }
    }

  implicit val codecProject: JsonValueCodec[Config.Project] =
    JsonCodecMaker.make[Config.Project](CodecMakerConfig)

  implicit val codecFile: JsonValueCodec[Config.File] =
    JsonCodecMaker.make[Config.File](CodecMakerConfig)

  def read(configDir: Path): Either[Throwable, Config.File] = {
    read(Files.readAllBytes(configDir))
  }

  def read(bytes: Array[Byte]): Either[Throwable, Config.File] = {
    Try(readFromArray[Config.File](bytes)) match {
      case Failure(exception) => Left(exception)
      case Success(value) => Right(value)
    }
  }

  def toStr(all: Config.File): String = {
    new String(writeToArray[Config.File](all), StandardCharsets.UTF_8)
  }
}
