# @Forward

An annotation processor for generating forwarders.

## Use

To create the equivalent of Guava's 100+ line
[`ForwardingDeque`](https://github.com/google/guava/blob/v18.0/guava/src/com/google/common/collect/ForwardingDeque.java)
you could write the following:
```java
import java.util.Deque;
import uno.perk.forward.Forward;

/**
 * A deque which forwards all its method calls to another deque. Subclasses
 * should override one or more methods to modify the behavior of the backing
 * deque as desired per the <a
 * href="http://en.wikipedia.org/wiki/Decorator_pattern">decorator pattern</a>.
 *
 * <p><b>Warning:</b> The methods of {@code ForwardingDeque} forward
 * <b>indiscriminately</b> to the methods of the delegate. For example,
 * overriding {@link #add} alone <b>will not</b> change the behavior of {@link
 * #offer} which can lead to unexpected behavior. In this case, you should
 * override {@code offer} as well.
 */
@Forward(Deque.class)
public abstract class ForwardingDeque<E> extends ForwardingDequeForwarder<E> {
  public ForwardingDeque(Deque<E> deque) {
    super(deque);
  }
}
```

The `@Forward` annotation will be processed and code will be generated for
`ForwardingDequeForwarder` which does all the work of forwarding all the `Deque` methods through 
the supplied `deque` delegate.

Alternatively, instead of generating a `ForwardingDeque` and extending it to add decoration, you
can just create an ad-hoc forwarder for immediate local use:
```java
@Forward(Deque.class)
public class InstrumentedDeque<E> extends InstrumentedDequeForwarder<E> {
  private long addFirstCalls;
  
  public InstrumentedDeque(Deque<E> deque) {
    super(deque);
  }
  
  @Override
  public void addFirst(E e) {
    addFirstCalls++;
    deque.addFirst(e);
  }
  
  public long getAddFirstCallCount() {
    return addFirstCalls;
  }
}
```

NB: The delegate field is exposed as a protected final field with the lower-camel-case name of its
type.

## Features

+ More than one type can be forwarded.
+ The name of the generated forwarder class can be customised via `@Forward`'s `forwarderPattern`.

## Known Limitations

+ Only interface types can be forwarded.

## Requirements

+ Java 7+

## Development

Pull requests are welcome.

We use [Travis CI](https://travis-ci.org) to verify the build
[![Build Status](https://travis-ci.org/perkuno/forward.svg?branch=master)](https://travis-ci.org/perkuno/forward).
