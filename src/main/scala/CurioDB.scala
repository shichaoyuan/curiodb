
package org.jupo.curiodb

import akka.actor.{ActorSystem, Actor, ActorSelection, ActorRef, ActorLogging, Props, PoisonPill}
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import scala.collection.mutable.{Set, Map, ArrayBuffer}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure, Random}
import java.net.InetSocketAddress


object Commands {

  val commands = Map(
    "StringNode" -> Set(
      "get", "set", "getset", "append", "getrange", "setrange", "strlen",
      "incr", "incrby", "incrbyfloat", "decr", "decrby", "bitcount",
      "bitop", "bitpos", "getbit", "setbit", "psetx", "setex", "setnx"
    ),
    "HashNode" -> Set(
      "hget", "hset", "hsetnx", "hgetall", "hkeys", "hvals", "hdel",
      "hexists", "hlen", "hmget", "hmset", "hincrby", "hincrbyfloat", "hscan"
    ),
    "ListNode" -> Set(
      "lpush", "lpop", "rpush", "rpop", "lpushx", "rpushx", "lset", "linsert",
      "lindex", "lrem", "lrange", "llen", "ltrim", "rpush", "rpop", "blpop",
      "brpop", "brpoplpush", "rpoplpush"
    ),
    "SetNode" -> Set(
      "sadd", "scard", "sismember", "smembers", "smove", "spop",
      "srandmember", "srem", "sdiff", "sinter", "sunion", "sdiffstore",
      "sinterstore", "sunionstore", "sscan"
    ),
    "KeyNode" -> Set(
      "keys", "add", "scan", "exists", "expire", "randomkey", "del"
    ),
    "ClientNode" -> Set(
      "mget", "mset", "msetnx"
    )
  )

  val mustExistsCommands = Set("lpushx", "rpushx")
  val cantExistsCommands = Set("setnx")

  def nodeMustExist(command: String) = mustExistsCommands.contains(command)
  def nodeCantExist(command: String) = cantExistsCommands.contains(command)

  def nodeType(command: String): String = {
    val matched = commands.filter {_._2.contains(command)}
    if (matched.size == 1) matched.keys.head else ""
  }

}


abstract class Node extends Actor with ActorLogging {

  implicit var args = Seq[String]()

  type Command = PartialFunction[String, Any]

  def command: Command

  def receive = {
    case payload: Payload =>
      args = payload.args
      val valid = payload.nodeType == getClass.getName.split('.').last
      sender() ! (if (valid) command(payload.command) else {
        s"Invalid command '${payload.command}' (${payload.nodeType} != ${getClass.getName})"
      })
  }

  def select(key: String): ActorSelection = {
    context.system.actorSelection(s"/user/$key")
  }

  def many[T](command: String, keys: Seq[String]): Seq[T] = {
    val timeout_ = 2 seconds
    implicit val timeout: Timeout = timeout_
    val futures = Future.traverse(keys.toList)(key => select(key) ? Payload(command, key))
    Await.result(futures, timeout_).asInstanceOf[Seq[T]]
  }

  def scan(values: Iterable[String]): Seq[String] = {
    val start = if (args.length >= 1) args(0).toInt else 0
    val filtered = if (args.length >= 2) {
      val regex = ("^" + args(1).map {
        case '.'|'('|')'|'+'|'|'|'^'|'$'|'@'|'%'|'\\' => "\\" + _
        case '*' => ".*"
        case '?' => "."
        case c => c
      }.mkString("") + "$").r
      values.filter(regex.pattern.matcher(_).matches)
    } else values
    val count = if (args.length >= 3) args(2).toInt else 10
    val end = start + count
    val next = if (end < filtered.size) end else 0
    Seq(next.toString) ++ filtered.slice(start, end)
  }

  def argPairs = (0 to args.length - 2 by 2).map {i => (args(i), args(i + 1))}

}


class StringNode extends Node {

  var value = ""

  def valueOrZero = if (value == "") "0" else value

  def command = {
    case "get"         => value
    case "set"         => value = args(0); "OK"
    case "setnx"       => command("set"); 1
    case "getset"      => val x = value; value = args(0); x
    case "append"      => value += args(0); value
    case "getrange"    => value.slice(args(0).toInt, args(1).toInt)
    case "setrange"    => value.patch(args(0).toInt, args(1), 1)
    case "strlen"      => value.length
    case "incr"        => value = (valueOrZero.toInt + 1).toString; value
    case "incrby"      => value = (valueOrZero.toInt + args(0).toInt).toString; value
    case "incrbyfloat" => value = (valueOrZero.toFloat + args(0).toFloat).toString; value
    case "decr"        => value = (valueOrZero.toInt - 1).toString; value
    case "decrby"      => value = (valueOrZero.toInt - args(0).toInt).toString; value
    case "bitcount"    => value.getBytes.map{_.toInt.toBinaryString.count(_ == "1")}.sum
    case "bitop"       => "Not implemented"
    case "bitpos"      => "Not implemented"
    case "getbit"      => "Not implemented"
    case "setbit"      => "Not implemented"
    case "psetx"       => "Not implemented"
    case "setex"       => "Not implemented"
  }

}


class HashNode extends Node {

  var value = Map[String, String]()

  def set(arg: Any): String = {val x = arg.toString; value(args(0)) = x; x}

  def command = {
    case "hget"         => value.get(args(0))
    case "hsetnx"       => if (!value.contains(args(0))) command("hset") else 0
    case "hgetall"      => value.map(x => Seq(x._1, x._2)).flatten
    case "hkeys"        => value.keys
    case "hvals"        => value.values
    case "hdel"         => val x = command("hexists"); value -= args(0); x
    case "hexists"      => if (value.contains(args(0))) 1 else 0
    case "hlen"         => value.size
    case "hmget"        => args.map(value.get(_))
    case "hmset"        => argPairs.foreach {args => value(args._1) = args._2}; "OK"
    case "hincrby"      => set(value.getOrElse(args(0), "0").toInt + args(1).toInt)
    case "hincrbyfloat" => set(value.getOrElse(args(0), "0").toFloat + args(1).toFloat)
    case "hscan"        => scan(value.keys)
    case "hset"         => val x = if (value.contains(args(0))) 0 else 1; set(args(1)); x
  }

}


class ListNode extends Node {

  var value = ArrayBuffer[String]()

  def slice = value.slice(args(0).toInt, args(1).toInt)

  def command = {
    case "lpush"      => args ++=: value; command("llen")
    case "rpush"      => value ++= args; command("llen")
    case "lpushx"     => command("lpush")
    case "rpushx"     => command("rpush")
    case "lpop"       => val x = value(0); value -= x; x
    case "rpop"       => val x = value.last; value.reduceToSize(value.length - 1); x
    case "lset"       => value(args(0).toInt) = args(1); "OK"
    case "lindex"     => value(args(0).toInt)
    case "lrem"       => value.remove(args(0).toInt)
    case "lrange"     => slice
    case "ltrim"      => value = slice; "OK"
    case "llen"       => value.length
    case "blpop"      => "Not implemented"
    case "brpop"      => "Not implemented"
    case "brpoplpush" => "Not implemented"
    case "rpoplpush"  => {
      val x = command("rpop")
      select(args(0)) ! Payload("lpush" +: args :+ x.toString:_*)
      x
    }
    case "linsert" => {
      val i = value.indexOf(args(1)) + (if (args(0) == "AFTER") 1 else 0)
      if (i >= 0) {value.insert(i, args(2)); command("llen")} else -1
    }
  }

}


class SetNode extends Node {

  var value = Set[String]()

  def command = {
    case "sadd"        => val x = (args.toSet &~ value).size; value ++= args; x
    case "srem"        => val x = (args.toSet & value).size; value --= args; x
    case "scard"       => value.size
    case "sismember"   => if (args.filter(!value.contains(_)).isEmpty) 1 else 0
    case "smembers"    => value
    case "srandmember" => value.toSeq(Random.nextInt(value.size))
    case "spop"        => val x = command("srandmember"); value -= x.toString; x
    case "sdiff"       => many[Set[String]]("smembers", args).fold(value)(_ &~ _)
    case "sinter"      => many[Set[String]]("smembers", args).fold(value)(_ & _)
    case "sunion"      => many[Set[String]]("smembers", args).fold(value)(_ | _)
    case "sdiffstore"  => value = many[Set[String]]("smembers", args).reduce(_ &~ _); command("scard")
    case "sinterstore" => value = many[Set[String]]("smembers", args).reduce(_ & _); command("scard")
    case "sunionstore" => value = many[Set[String]]("smembers", args).reduce(_ | _); command("scard")
    case "sscan"       => scan(value)
    case "smove"       => {
      if (value.contains(args(1))) {
        value -= args(1)
        select(args(0)) ! Payload("sadd" +: args:_*)
        1
      } else 0
    }
  }

}


class KeyNode extends SetNode {
  override def command = ({
    case "add"       => value += args(0)
    case "keys"      => command("smembers")
    case "scan"      => command("sscan")
    case "exists"    => command("sismember")
    case "randomkey" => command("srandmember")
    case "del"       => val x = args.filter(value.contains(_)).map(select(_) ! PoisonPill); value --= args; x.length
  }: Command) orElse super.command
}


case class Payload(input: Any*) {
  val command = if (input.length > 0) input(0).toString else ""
  val nodeType = Commands.nodeType(command)
  val isClientCommand = nodeType == "ClientNode"
  val isKeyCommand = nodeType == "KeyNode"
  val key = if (input.length > 1 && !isClientCommand && !isKeyCommand) input(1).toString else if (isKeyCommand) "keys" else ""
  val args = input.slice(if (isClientCommand || isKeyCommand) 1 else 2, input.length).map(_.toString)
}


class ClientNode extends Node with ActorLogging {

  val buffer = new StringBuilder()
  val timeout_ = 10 seconds
  implicit val timeout: Timeout = timeout_

  def command = {
    case "mget" => many[String]("get", args)
    case "msetnx" =>
      val future = select("keys") ? Payload("exists" +: argPairs.map(_._1):_*)
      val result = Await.result(future, timeout_).asInstanceOf[Int]
      if (result == 1) command("mset")
      result
    case "mset" =>
      argPairs.foreach {args =>
        val payload = Payload("set", args._1, args._2)
        select(args._1).resolveOne(timeout_).onComplete {
          case Success(node) => node ! payload
          case Failure(_)    => create(Props[StringNode], args._1) ! payload
        }
      }
      "OK"
  }

  def create(nodeType: Props, key: String): ActorRef = {
    select("keys") ! Payload("add", key)
    context.system.actorOf(nodeType, key)
  }

  def request(node: ActorRef, payload: Payload) = {
    Await.result(node ? payload, timeout_).asInstanceOf[Any]
  }

  def respond(client: ActorRef, payload: Payload, response: Any) = {
    val message = response match {
      case x: Iterable[String] => x.mkString("\n")
      case x => x.toString
    }
    log.info(s"Sending $message".replace("\n", " "))
    client ! Tcp.Write(ByteString(s"$message\n"))
  }

  def handle(client: ActorRef, payload: Payload) = {
    select(payload.key).resolveOne(timeout_).onComplete {
      case Success(node) =>
        val cantExist = Commands.nodeCantExist(payload.command)
        respond(client, payload, if (!cantExist) request(node, payload) else 0)
      case Failure(_) =>
        val response = if (!Commands.nodeMustExist(payload.command)) {
          val nodeType = payload.nodeType match {
            case "StringNode" => Props[StringNode]
            case "HashNode"   => Props[HashNode]
            case "ListNode"   => Props[ListNode]
            case "SetNode"    => Props[SetNode]
          }
          request(create(nodeType, payload.key), payload)
        } else 0
        respond(client, payload, response)
    }
  }

  override def receive = {
    case Tcp.PeerClosed => log.info("Disconnected"); context stop self
    case Tcp.Received(data) =>
      val received = data.utf8String
      val shortened = received.trim.slice(0, 100).replace("\n", " ")
      buffer.append(received)
      if (!received.endsWith("\n")) {
        log.info(s"Received part $shortened")
      } else {
        log.info(s"Received all $shortened")
        val client = sender()
        val payload = new Payload(buffer.stripLineEnd.split(' '):_*)
        buffer.clear()
        args = payload.args
        if (payload.nodeType == "") {
          respond(client, payload, "Unknown command")
        } else if (payload.isClientCommand) {
          respond(client, payload, command(payload.command))
        } else if (payload.key == "") {
          respond(client, payload, "Too few paramaters")
        } else {
          handle(client, payload)
        }
      }
  }

}


class Server(host: String, port: Int) extends Actor with ActorLogging {

  import context.system

  IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(host, port))

  def receive = {
    case Tcp.Bound(local) => log.info(s"Listening on $local")
    case Tcp.Connected(remote, local) =>
      log.info(s"Accepted connection from $remote")
      sender() ! Tcp.Register(context.actorOf(Props[ClientNode]))
  }

}


object Actis extends App {
  val system = ActorSystem()
  system.actorOf(Props[KeyNode], "keys")
  system.actorOf(Props(new Server("localhost", 9999)), "server")
  system.awaitTermination()
}
