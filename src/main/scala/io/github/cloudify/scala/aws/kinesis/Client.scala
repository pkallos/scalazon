package io.github.cloudify.scala.aws.kinesis

import scala.language.implicitConversions
import com.amazonaws.services.kinesis.{AmazonKinesisClient, AmazonKinesis}
import com.amazonaws.services.kinesis.model
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConversions._
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider}
import Implicits._
import scala.util.{Failure, Success, Try}

/**
 * The Kinesis API client.
 *
 * This class is composed of methods that execute `Requests` generated by the
 * DSL methods. Every API call is mapped to a `Future`.
 * An implicit `ExecutionContext` must be in scope.
 *
 * Example:
 * {{{
 *  import scala.concurrent.ExecutionContext.Implicits.global
 *  import io.github.cloudify.scala.aws.kinesis.Requests._
 *  import io.github.cloudify.scala.aws.kinesis.Definitions._
 *
 *  val streamDef = Stream("myStream")
 *  val createStreamReq = CreateStream(streamRef)
 *  client.execute(createStreamRef)
 * }}}
 */
trait Client {
  import Definitions._

  def execute(r: Requests.CreateStream)(implicit ec: ExecutionContext): Future[Stream]

  def execute(r: Requests.DeleteStream)(implicit ec: ExecutionContext): Future[Unit]

  def execute(r: Requests.TryDescribeStream)(implicit ec: ExecutionContext): Future[StreamDescription]

  def execute(r: Requests.DescribeStream)(implicit ec: ExecutionContext): Future[StreamDescription]

  def execute(r: Requests.WaitStreamActive)(implicit ec: ExecutionContext): Future[StreamDescription]

  def execute(r: Requests.ListStreams)(implicit ec: ExecutionContext): Future[Iterable[String]]

  def execute(r: Requests.PutRecord)(implicit ec: ExecutionContext): Future[Unit]

  def execute(r: Requests.ListStreamShards)(implicit ec: ExecutionContext): Future[Iterable[Shard]]

  def execute(r: Requests.ShardIterator)(implicit ec: ExecutionContext): Future[ShardIterator]

  def execute(r: Requests.NextRecords)(implicit ec: ExecutionContext): Future[NextRecords]

}

/**
 * Implementation of the Kinesis API client.
 *
 * This class is composed of methods that execute `Requests` generated by the
 * DSL methods.
 * The implementation is based on the official Amazon Kinesis API.
 */
class ClientImpl(val kinesisClient: AmazonKinesis) extends Client {
  import Definitions._

  def execute(r: Requests.CreateStream)(implicit ec: ExecutionContext): Future[Stream] = Future {
    val createStreamRequest = new model.CreateStreamRequest()
    createStreamRequest.setStreamName(r.streamDef.name)
    createStreamRequest.setShardCount(r.size)
    Try(kinesisClient.createStream(createStreamRequest)) match {
      case Success(_) => Stream(r.streamDef.name)
      case Failure(t: model.ResourceInUseException) if t.getStatusCode == 400 => Stream(r.streamDef.name)
      case Failure(t) => throw new Exception(t)
    }
  }

  def execute(r: Requests.DeleteStream)(implicit ec: ExecutionContext): Future[Unit] = Future {
    val deleteStreamRequest = new model.DeleteStreamRequest()
    deleteStreamRequest.setStreamName(r.streamDef.name)
    kinesisClient.deleteStream(deleteStreamRequest)
  }

  def execute(r: Requests.TryDescribeStream)(implicit ec: ExecutionContext): Future[StreamDescription] = Future {
    val describeStreamRequest = new model.DescribeStreamRequest()
    describeStreamRequest.setStreamName( r.streamDef.name )
    val describeStreamResponse = kinesisClient.describeStream( describeStreamRequest )
    StreamDescription(r.streamDef, describeStreamResponse)
  }

  def execute(r: Requests.DescribeStream)(implicit ec: ExecutionContext): Future[StreamDescription] = {
    val tryDescribeReq = Requests.TryDescribeStream(r.streamDef)
    Future.retry(r.retries, r.sleep) { execute(tryDescribeReq) }
  }

  def execute(r: Requests.WaitStreamActive)(implicit ec: ExecutionContext): Future[StreamDescription] = {
    val describeReq = Requests.DescribeStream(r.streamDef)
    Future.retry(r.retries, r.sleep) { execute(describeReq).filter(_.isActive) }
  }

  def execute(r: Requests.ListStreams)(implicit ec: ExecutionContext): Future[Iterable[String]] = Future {
    val listStreamsRequest = new model.ListStreamsRequest()
    listStreamsRequest.setLimit(10)

    @tailrec
    def run(names: Seq[String] = Seq()): Seq[String] = {
      val listStreamsResult = kinesisClient.listStreams(listStreamsRequest)
      val streamNames = names ++ listStreamsResult.getStreamNames
      streamNames.lastOption.foreach { name =>
        listStreamsRequest.setExclusiveStartStreamName(name)
      }
      if(listStreamsResult.isMoreDataAvailable) {
        run(streamNames)
      } else streamNames
    }

    run()
  }

  def execute(r: Requests.PutRecord)(implicit ec: ExecutionContext): Future[Unit] = Future {
    val putRecordRequest = new model.PutRecordRequest()
    putRecordRequest.setStreamName(r.streamDef.name)
    putRecordRequest.setData(r.data)
    r.minSeqNumber.foreach { n => putRecordRequest.setExclusiveMinimumSequenceNumber(n) }
    putRecordRequest.setPartitionKey(r.partitionKey)
    val putRecordResult = kinesisClient.putRecord(putRecordRequest)
    PutResult(putRecordResult)
  }

  def execute(r: Requests.ListStreamShards)(implicit ec: ExecutionContext): Future[Iterable[Shard]] = Future {
    val describeStreamRequest = new model.DescribeStreamRequest()
    describeStreamRequest.setStreamName(r.streamDef.name)

    // @tailrec
    def run(shards: Seq[model.Shard] = Seq()): Seq[model.Shard] = {
      val describeStreamResult = kinesisClient.describeStream( describeStreamRequest )
      val newShards: Seq[model.Shard] = shards ++ describeStreamResult.getStreamDescription.getShards
      newShards.lastOption.foreach { lastShard =>
        describeStreamRequest.setExclusiveStartShardId( lastShard.getShardId )
      }
      /* if(describeStreamResult.isMoreDataAvailable) {
        run(newShards)
      } else */ newShards
    }

    run().map { shard =>
      Shard(r.streamDef, shard)
    }
  }

  def execute(r: Requests.ShardIterator)(implicit ec: ExecutionContext): Future[ShardIterator] = Future {
    val getShardIteratorRequest = new model.GetShardIteratorRequest()

    getShardIteratorRequest.setStreamName(r.shardDef.streamDef.name)
    getShardIteratorRequest.setShardId(r.shardDef.shard.getShardId)
    getShardIteratorRequest.setShardIteratorType(r.iteratorType.value)

    val getShardIteratorResult = kinesisClient.getShardIterator(getShardIteratorRequest)

    ShardIterator(getShardIteratorResult.getShardIterator, r.shardDef)
  }

  def execute(r: Requests.NextRecords)(implicit ec: ExecutionContext): Future[NextRecords] = Future {
    val getNextRecordsRequest = new model.GetNextRecordsRequest()
    getNextRecordsRequest.setShardIterator(r.iteratorDef.name)
    getNextRecordsRequest.setLimit(r.limit)

    val result = kinesisClient.getNextRecords(getNextRecordsRequest)
    NextRecords(r.iteratorDef, result)
  }

}

object Client {

  /**
   * Creates a client from an `AmazonKinesis` client.
   */
  def fromClient(kinesisClient: AmazonKinesis): Client = new ClientImpl(kinesisClient)

  /**
   * Creates a client from an `AWSCredentialsProvider`.
   */
  def fromCredentials(credentialsProvider: AWSCredentialsProvider): Client = {
    val kinesisClient = new AmazonKinesisClient(credentialsProvider)
    fromClient(kinesisClient)
  }

  /**
   * Creates a client from an access key and a secret key.
   */
  def fromCredentials(accessKey: String, secretKey: String): Client = {
    val credentials = new AWSCredentials {
      def getAWSAccessKeyId: String = accessKey
      def getAWSSecretKey: String = secretKey
    }
    val kinesisClient = new AmazonKinesisClient(credentials)
    fromClient(kinesisClient)
  }

  /**
   * Implicitly converts the requests into `Future`s.
   * Requires an implicit `Client` and an implicit `ExecutionContext` in scope.
   */
  object ImplicitExecution {

    implicit def implicitExecute(r: Requests.CreateStream)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.DeleteStream)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.WaitStreamActive)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.TryDescribeStream)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.DescribeStream)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.ListStreams)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.ListStreamShards)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.ShardIterator)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.NextRecords)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

    implicit def implicitExecute(r: Requests.PutRecord)(implicit client: Client, ec: ExecutionContext) =
      client.execute(r)(ec)

  }

}