package uno.perk.forward;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ForwardTests {
  public static class SimpleTest {
    interface StringableInt {
      String toString(int value);
    }

    @Forward(StringableInt.class)
    static class Simple extends StringableIntForwarder {
      public Simple(StringableInt delegate) {
        super(delegate);
      }
    }

    @Test
    public void testSimple() {
      StringableInt delegate = new StringableInt() {
        @Override public String toString(int value) {
          return String.valueOf(value);
        }
      };
      assertEquals("42", new Simple(delegate).toString(42));
    }
  }

  public static class ParameterizedTest {
    interface ComplexParameterized<S, T extends RunnableFuture<S>, E extends Exception> {
      String get(String message, T future) throws InterruptedException, ExecutionException, E;
    }

    @Forward(ComplexParameterized.class)
    static class Parameterized<S, T extends RunnableFuture<S>, E extends Exception>
        extends ComplexParameterizedForwarder<S, T, E> {

      public Parameterized(ComplexParameterized<S, T, E> delegate) {
        super(delegate);
      }
    }

    @Test
    public void testParameterized() throws Exception {
      class IntFuture implements RunnableFuture<Integer> {
        @Override public void run() {
          // noop
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          return false;
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public boolean isDone() {
          return false;
        }

        @Override
        public Integer get() throws InterruptedException, ExecutionException {
          return 42;
        }

        @Override
        public Integer get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
          return get();
        }
      }
      ComplexParameterized<Integer, IntFuture, IOException> delegate =
          new ComplexParameterized<Integer, IntFuture, IOException>() {
            @Override
            public String get(String message, IntFuture future)
                throws InterruptedException, ExecutionException {

              return String.format("%s: %d", message, future.get());
            }
          };
      Parameterized<Integer, IntFuture, IOException> parameterized =
          new Parameterized<Integer, IntFuture, IOException>(delegate);

      assertEquals("Jake: 42", parameterized.get("Jake", new IntFuture()));
    }
  }

  public static class DelegateNameTest {
    @Forward(value = Runnable.class, delegateName = "bob")
    static class CustomDelegateName extends RunnableForwarder {
      public CustomDelegateName(Runnable delegate) {
        super(delegate);
      }
      public Runnable delegate() {
        return bob;
      }
    }

    @Test
    public void testCustomDelegateName() throws Exception {
      final AtomicBoolean ran = new AtomicBoolean(false);
      Runnable delegate = new Runnable() {
        @Override public void run() {
          ran.set(true);
        }
      };
      CustomDelegateName customDelegateName = new CustomDelegateName(delegate);

      assertFalse(ran.get());
      customDelegateName.run();
      assertTrue(ran.get());
      assertSame(delegate, customDelegateName.delegate());
    }
  }

  public static class ForwarderNameTest {
    @Forward(value=Closeable.class, forwarderPattern = "Forwarding$1")
    static class CustomForwarderName extends ForwardingCloseable {
      public CustomForwarderName(Closeable delegate) {
        super(delegate);
      }
    }

    @Test
    public void testCustomForwarderName() throws IOException {
      final AtomicBoolean closed = new AtomicBoolean(false);
      Closeable delegate = new Closeable() {
        @Override public void close() {
          closed.set(true);
        }
      };
      CustomForwarderName customDelegateName = new CustomForwarderName(delegate);

      assertFalse(closed.get());
      customDelegateName.close();
      assertTrue(closed.get());
    }
  }
}

