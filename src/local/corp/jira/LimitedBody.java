package local.corp.jira;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects a bounded response within HttpClient's request timeout, including body transfer. */
final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
  private final long limit;
  private long received;
  private Flow.Subscription subscription;
  private boolean finished;
  LimitedBody(long limit){this.limit=limit;}
  static HttpResponse.BodyHandler<byte[]> handler(long limit){return info->new LimitedBody(limit);}
  public CompletionStage<byte[]> getBody(){return delegate.getBody();}
  public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
  public void onNext(List<ByteBuffer> buffers){if(finished)return;for(ByteBuffer b:buffers){received+=b.remaining();if(received>limit){finished=true;subscription.cancel();delegate.onError(new IOException(I18n.t("Ответ Jira превышает допустимый размер (")+limit/1048576+I18n.t(" МБ)")));return;}}delegate.onNext(buffers);}
  public void onError(Throwable error){if(!finished){finished=true;delegate.onError(error);}}
  public void onComplete(){if(!finished){finished=true;delegate.onComplete();}}
}
