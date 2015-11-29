package uno.perk.forward;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ForwardTests {
  public static class SimpleTest {
    interface StringableInt {
      String toString(int value);
    }

    @Forward(StringableInt.class)
    static class Simple extends SimpleForwarder {
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
        extends ParameterizedForwarder<S, T, E> {

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

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
          return false;
        }

        @Override public boolean isCancelled() {
          return false;
        }

        @Override public boolean isDone() {
          return false;
        }

        @Override public Integer get() throws InterruptedException, ExecutionException {
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
      Parameterized<Integer, IntFuture, IOException> parameterized = new Parameterized<>(delegate);

      assertEquals("Jake: 42", parameterized.get("Jake", new IntFuture()));
    }
  }

  public static class ForwarderNameTest {
    @Forward(value=Closeable.class, forwarderPattern = "Forwarding$1")
    static class CustomForwarderName extends ForwardingCustomForwarderName {
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

  public static class MultipleForwardsTest {
    @Forward({Closeable.class, Callable.class})
    static class Multiple<T> extends MultipleForwarder<T> {
      public Multiple(Closeable closeable, Callable<T> callable) {
        super(closeable, callable);
      }
    }

    @Test
    public void testMultipleForwarders() throws Exception {
      final AtomicBoolean closed = new AtomicBoolean(false);
      Closeable closeable = new Closeable() {
        @Override public void close() {
          closed.set(true);
        }
      };
      Callable<String> callable = new Callable<String>() {
        @Override
        public String call() throws Exception {
          return "Jake";
        }
      };
      Multiple<String> multiple = new Multiple<>(closeable, callable);

      assertFalse(closed.get());
      multiple.close();
      assertTrue(closed.get());

      assertEquals("Jake", multiple.call());
    }
  }
}

