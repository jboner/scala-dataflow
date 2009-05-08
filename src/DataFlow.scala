/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.dataflow

import scala.actors.Actor
import scala.actors.OutputChannel
import scala.actors.Future
import scala.actors.Actor._

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue}

object DataFlow {
  
  def thread(body: => Unit) = { 
    val thread = new IsolatedEventBasedThread(body).start
    thread ! 'start
    thread
  }

  def thread[MessageType, ReturnType](body: MessageType => ReturnType) = 
    new ReactiveEventBasedThread(body).start

  private class IsolatedEventBasedThread(body: => Unit) extends Actor {
    def act = loop { 
      react {
        case 'start => body
        case 'exit => exit()
      }
    }
  }

  private class ReactiveEventBasedThread[MessageType, ReturnType](body: MessageType => ReturnType) extends Actor {
    def act = loop { 
      react {
        case message: MessageType => sender ! body(message)
        case 'exit => exit()
      }
    }
  }

  sealed class DataFlowVariable[T] {
  
    private sealed abstract class DataFlowVariableMessage
    private case class Set[T](value: T) extends DataFlowVariableMessage
    private case object Get extends DataFlowVariableMessage
  
    private val value = new AtomicReference[Option[T]](None)
    private val blockedReaders = new ConcurrentLinkedQueue[Actor] 

    private class In[T](dataFlow: DataFlowVariable[T]) extends Actor {
      def act = loop { react {
        case Set(v) => 
          if (dataFlow.value.compareAndSet(None, Some(v.asInstanceOf[T]))) { 
            val iterator = dataFlow.blockedReaders.iterator
            while (iterator.hasNext) iterator.next ! Set(v)
            dataFlow.blockedReaders.clear
          } else throw new DataFlowVariableException(
            "Attempt to change data flow variable (from [" + dataFlow.value.get + "] to [" + v + "])")
        case 'exit =>  exit()
      }}
    }
  
    private class Out[T](dataFlow: DataFlowVariable[T]) extends Actor {
      var reader: Option[OutputChannel[Any]] = None
      def act = loop { react {
        case Get => 
          val ref = dataFlow.value.get
          if (ref.isDefined) reply(ref.get) else reader = Some(sender)
        case Set(v) => if (reader.isDefined) reader.get ! v
        case 'exit =>  exit()
      }}
    }
  
    private[this] val in = { val in = new In(this); in.start; in }

    def <<(ref: DataFlowVariable[T]) = in ! Set(ref())

    def <<(value: T) = in ! Set(value)
  
    def apply(): T = { 
      val ref = value.get
      if (ref.isDefined) ref.get
      else {
        val out = { val out = new Out(this); out.start; out }
        blockedReaders.offer(out)
        val future: Future[T] = out !! (Get, {case t: T => t})
        val result = future()
        out ! 'exit
        result    
      }
    }
  
    def shutdown = in ! 'exit
  }

  class DataFlowStream[T] extends Seq[T] { 
    private[this] val queue = new LinkedBlockingQueue[DataFlowVariable[T]]

    def <<<(ref: DataFlowVariable[T]) = queue.offer(ref)
    
    def <<<(value: T) = { 
      val ref = new DataFlowVariable[T]
      ref << value
      queue.offer(ref)
    }   
   
    def apply(): T = {
      val ref = queue.take
      ref()
    }
    
    def take: DataFlowVariable[T] = queue.take

    //==== For Seq ====
    
    def length: Int = queue.size

    def apply(i: Int): T = {
      if (i == 0) apply()
      else throw new UnsupportedOperationException("Access by index other than '0' is not supported by DataFlowSream")
    } 
    
    override def elements: Iterator[T] = new Iterator[T] {
      private val iter = queue.iterator
      def hasNext: Boolean = iter.hasNext
      def next: T = { val ref = iter.next; ref() }
    }

    override def toList: List[T] = queue.toArray.toList.asInstanceOf[List[T]]
  }
  
  class DataFlowVariableException(msg: String) extends RuntimeException(msg)
}


// ==========================
// ======== EXAMPLES ========
// ==========================

object Test1 extends Application {

  // =======================================
  // This example is rom Oz wikipedia page: http://en.wikipedia.org/wiki/Oz_(programming_language)

  /* 
  thread 
    Z = X+Y     % will wait until both X and Y are bound to a value.
    {Browse Z}  % shows the value of Z.
  end
  thread X = 40 end
  thread Y = 2 end
  */

  import DataFlow._
  val x, y, z = new DataFlowVariable[Int]
  thread {
    z << x() + y()
    println("z = " + z())
    System.exit(0)
  }
  thread { x << 40 }
  thread { y << 2 }
}

// =======================================
object Test2 extends Application {

  /*
  fun {Ints N Max}
    if N == Max then nil
    else 
      {Delay 1000}
      N|{Ints N+1 Max}
    end
  end

  fun {Sum S Stream}
    case Stream of nil then S
    [] H|T then S|{Sum H+S T} end
  end

  local X Y in
    thread X = {Ints 0 1000} end
    thread Y = {Sum 0 X} end
    {Browse Y}
  end
  */

  import DataFlow._

  def ints(n: Int, max: Int): List[Int] =
    if (n == max) Nil
    else n :: ints(n + 1, max)

  def sum(s: Int, stream: List[Int]): List[Int] = stream match {
    case Nil => s :: Nil
    case h :: t => s :: sum(h + s, t)
  }

  val x = new DataFlowVariable[List[Int]]
  val y = new DataFlowVariable[List[Int]]

  thread { x << ints(0, 1000) }
  thread { y << sum(0, x()) }
  thread { println("List of sums: " + y()); System.exit(0) }
}

// =======================================
object Test3 extends Application {

  // Using DataFlowStream and foldLeft to calculate sum
  
  /*
  fun {Ints N Max}
    if N == Max then nil
    else 
      {Delay 1000}
      N|{Ints N+1 Max}
    end
  end

  fun {Sum S Stream}
    case Stream of nil then S
    [] H|T then S|{Sum H+S T} end
  end

  local X Y in
    thread X = {Ints 0 1000} end
    thread Y = {Sum 0 X} end
    {Browse Y}
  end
  */

  import DataFlow._

  def ints(n: Int, max: Int, stream: DataFlowStream[Int]): Unit = if (n != max) { 
    println("Generating int: " + n)
    stream <<< n
    ints(n + 1, max, stream)
  }

  def sum(s: Int, in: DataFlowStream[Int], out: DataFlowStream[Int]): Unit = { 
    println("Calculating: " + s)
    out <<< s
    sum(in() + s, in, out)
  }

  def printSum(stream: DataFlowStream[Int]): Unit = {
    println("Result: " + stream())      
    printSum(stream)
  }

  val producer = new DataFlowStream[Int]
  val consumer = new DataFlowStream[Int]

  thread { ints(0, 1000, producer) }
  thread { 
    Thread.sleep(1000)
    println("Sum: " + producer.map(x => x * x).foldLeft(0)(_ + _)) 
    System.exit(0)
  }
}


// =======================================
object Test4 extends Application { 

  // Using DataFlowStream and recursive function to calculate sum
  
  /*
  fun {Ints N Max}
    if N == Max then nil
    else 
      {Delay 1000}
      N|{Ints N+1 Max}
    end
  end

  fun {Sum S Stream}
    case Stream of nil then S
    [] H|T then S|{Sum H+S T} end
  end

  local X Y in
    thread X = {Ints 0 1000} end
    thread Y = {Sum 0 X} end
    {Browse Y}
  end
  */

  import DataFlow._

  def ints(n: Int, max: Int, stream: DataFlowStream[Int]): Unit = if (n != max) { 
    println("Generating int: " + n)
    stream <<< n
    ints(n + 1, max, stream)
  }

  def sum(s: Int, in: DataFlowStream[Int], out: DataFlowStream[Int]): Unit = { 
    println("Calculating: " + s)
    out <<< s
    sum(in() + s, in, out)
  }

  def printSum(stream: DataFlowStream[Int]): Unit = {
    println("Result: " + stream())      
    printSum(stream)
  }

  val producer = new DataFlowStream[Int]
  val consumer = new DataFlowStream[Int]

  thread { ints(0, 1000, producer) }
  thread { sum(0, producer, consumer) }
  thread { printSum(consumer) }
}


// =======================================
object Test5 extends Application {
  import DataFlow._

  // create four 'Int' data flow variables
  val x, y, z, v = new DataFlowVariable[Int]

  val main = thread {
    println("Thread 'main'")
   
    x << 1
    println("'x' set to: " + x())
   
    println("Waiting for 'y' to be set...")
   
    if (x() > y()) { 
      z << x
      println("'z' set to 'x': " + z())
    } else { 
      z << y
      println("'z' set to 'y': " + z())
    }
  
    // main completed, shut down the data flow variables
    x.shutdown
    y.shutdown
    z.shutdown
    v.shutdown
  }

  val setY = thread {
    println("Thread 'setY', sleeping...")
    Thread.sleep(5000)
    y << 2
    println("'y' set to: " + y())
  }  

  val setV = thread {
    println("Thread 'setV'")
    v << y
    println("'v' set to 'y': " + v())  
  }

  // shut down the threads  
  main ! 'exit
  setY ! 'exit
  setV ! 'exit

  System.exit(0)
}


