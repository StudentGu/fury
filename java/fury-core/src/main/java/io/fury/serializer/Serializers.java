/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.serializer;

import static io.fury.util.function.Functions.makeGetterFunction;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Primitives;
import io.fury.Fury;
import io.fury.collection.Tuple2;
import io.fury.memory.MemoryBuffer;
import io.fury.resolver.ClassResolver;
import io.fury.type.Type;
import io.fury.util.Platform;
import io.fury.util.Utils;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Currency;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Serialization utils and common serializers.
 *
 * @author chaokunyang
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class Serializers {

  /**
   * Serializer subclass must have a constructor which take parameters of type {@link Fury} and
   * {@link Class}, or {@link Fury} or {@link Class} or no-arg constructor.
   */
  public static <T> Serializer<T> newSerializer(
      Fury fury, Class type, Class<? extends Serializer> serializerClass) {
    Serializer serializer = fury.getClassResolver().getSerializer(type, false);
    try {
      if (serializerClass == ObjectSerializer.class) {
        return new ObjectSerializer(fury, type);
      }
      if (serializerClass == CompatibleSerializer.class) {
        return new CompatibleSerializer(fury, type);
      }
      try {
        Constructor<? extends Serializer> ctr =
            serializerClass.getConstructor(Fury.class, Class.class);
        ctr.setAccessible(true);
        return ctr.newInstance(fury, type);
      } catch (NoSuchMethodException e) {
        Utils.ignore(e);
      }
      try {
        Constructor<? extends Serializer> ctr = serializerClass.getConstructor(Fury.class);
        ctr.setAccessible(true);
        return ctr.newInstance(fury);
      } catch (NoSuchMethodException e) {
        Utils.ignore(e);
      }
      try {
        Constructor<? extends Serializer> ctr = serializerClass.getConstructor(Class.class);
        ctr.setAccessible(true);
        return ctr.newInstance(type);
      } catch (NoSuchMethodException e) {
        Utils.ignore(e);
      }
      return serializerClass.newInstance();
    } catch (InvocationTargetException e) {
      fury.getClassResolver().resetSerializer(type, serializer);
      if (e.getCause() != null) {
        Platform.throwException(e.getCause());
      } else {
        Platform.throwException(e);
      }
    } catch (Throwable t) {
      // Some serializer may set itself in constructor as serializer, but the
      // constructor failed later. For example, some final type field doesn't
      // support serialization.
      fury.getClassResolver().resetSerializer(type, serializer);
      Platform.throwException(t);
    }
    throw new IllegalStateException("unreachable");
  }

  public static Object readPrimitiveValue(Fury fury, MemoryBuffer buffer, short classId) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        return buffer.readBoolean();
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        return buffer.readByte();
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        return buffer.readChar();
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        return buffer.readShort();
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fury.compressInt()) {
          return buffer.readVarInt();
        } else {
          return buffer.readInt();
        }
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        return buffer.readFloat();
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        if (fury.compressLong()) {
          return buffer.readVarLong();
        } else {
          return buffer.readLong();
        }
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        return buffer.readDouble();
      default:
        {
          throw new IllegalStateException("unreachable");
        }
    }
  }

  public abstract static class CrossLanguageCompatibleSerializer<T> extends Serializer<T> {
    private final short typeId;

    public CrossLanguageCompatibleSerializer(Fury fury, Class<T> cls, short typeId) {
      super(fury, cls);
      this.typeId = typeId;
    }

    public CrossLanguageCompatibleSerializer(
        Fury fury, Class<T> cls, short typeId, boolean needToWriteRef) {
      super(fury, cls, needToWriteRef);
      this.typeId = typeId;
    }

    @Override
    public short getXtypeId() {
      return typeId;
    }

    @Override
    public void xwrite(MemoryBuffer buffer, T value) {
      write(buffer, value);
    }

    @Override
    public T xread(MemoryBuffer buffer) {
      return read(buffer);
    }
  }

  static Tuple2<ToIntFunction, Function> builderCache;

  private static synchronized Tuple2<ToIntFunction, Function> getBuilderFunc() {
    if (builderCache == null) {
      Function getValue =
          (Function) makeGetterFunction(StringBuilder.class.getSuperclass(), "getValue");
      if (Platform.JAVA_VERSION > 8) {
        try {
          Method getCoderMethod = StringBuilder.class.getSuperclass().getDeclaredMethod("getCoder");
          ToIntFunction<CharSequence> getCoder =
              (ToIntFunction<CharSequence>) makeGetterFunction(getCoderMethod, int.class);
          builderCache = Tuple2.of(getCoder, getValue);
        } catch (NoSuchMethodException e) {
          throw new RuntimeException(e);
        }

      } else {
        builderCache = Tuple2.of(null, getValue);
      }
    }
    return builderCache;
  }

  public abstract static class AbstractStringBuilderSerializer<T extends CharSequence>
      extends Serializer<T> {
    protected final ToIntFunction getCoder;
    protected final Function getValue;
    protected final StringSerializer stringSerializer;

    public AbstractStringBuilderSerializer(Fury fury, Class<T> type) {
      super(fury, type);
      Tuple2<ToIntFunction, Function> builderFunc = getBuilderFunc();
      getCoder = builderFunc.f0;
      getValue = builderFunc.f1;
      stringSerializer = new StringSerializer(fury);
    }

    @Override
    public void xwrite(MemoryBuffer buffer, T value) {
      stringSerializer.writeUTF8String(buffer, value.toString());
    }

    @Override
    public short getXtypeId() {
      return (short) -Type.STRING.getId();
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      if (Platform.JAVA_VERSION > 8) {
        int coder = getCoder.applyAsInt(value);
        byte[] v = (byte[]) getValue.apply(value);
        buffer.writeByte(coder);
        if (coder == 0) {
          buffer.writePrimitiveArrayWithSizeEmbedded(v, Platform.BYTE_ARRAY_OFFSET, value.length());
        } else {
          if (coder != 1) {
            throw new UnsupportedOperationException("Unsupported coder " + coder);
          }
          buffer.writePrimitiveArrayWithSizeEmbedded(
              v, Platform.BYTE_ARRAY_OFFSET, value.length() << 1);
        }
      } else {
        char[] v = (char[]) getValue.apply(value);
        if (StringSerializer.isAscii(v)) {
          stringSerializer.writeJDK8Ascii(buffer, v, value.length());
        } else {
          stringSerializer.writeJDK8UTF16(buffer, v, value.length());
        }
      }
    }
  }

  public static final class StringBuilderSerializer
      extends AbstractStringBuilderSerializer<StringBuilder> {

    public StringBuilderSerializer(Fury fury) {
      super(fury, StringBuilder.class);
    }

    @Override
    public StringBuilder read(MemoryBuffer buffer) {
      return new StringBuilder(stringSerializer.readJavaString(buffer));
    }

    @Override
    public StringBuilder xread(MemoryBuffer buffer) {
      return new StringBuilder(stringSerializer.readUTF8String(buffer));
    }
  }

  public static final class StringBufferSerializer
      extends AbstractStringBuilderSerializer<StringBuffer> {

    public StringBufferSerializer(Fury fury) {
      super(fury, StringBuffer.class);
    }

    @Override
    public StringBuffer read(MemoryBuffer buffer) {
      return new StringBuffer(stringSerializer.readJavaString(buffer));
    }

    @Override
    public StringBuffer xread(MemoryBuffer buffer) {
      return new StringBuffer(stringSerializer.readUTF8String(buffer));
    }
  }

  public static final class EnumSerializer extends Serializer<Enum> {
    private final Enum[] enumConstants;

    public EnumSerializer(Fury fury, Class<Enum> cls) {
      super(fury, cls, false);
      if (cls.isEnum()) {
        enumConstants = cls.getEnumConstants();
      } else {
        Preconditions.checkArgument(Enum.class.isAssignableFrom(cls) && cls != Enum.class);
        @SuppressWarnings("unchecked")
        Class<Enum> enclosingClass = (Class<Enum>) cls.getEnclosingClass();
        Preconditions.checkNotNull(enclosingClass);
        Preconditions.checkArgument(enclosingClass.isEnum());
        enumConstants = enclosingClass.getEnumConstants();
      }
    }

    @Override
    public void write(MemoryBuffer buffer, Enum value) {
      buffer.writePositiveVarInt(value.ordinal());
    }

    @Override
    public Enum read(MemoryBuffer buffer) {
      return enumConstants[buffer.readPositiveVarInt()];
    }
  }

  public static final class BigDecimalSerializer extends Serializer<BigDecimal> {
    public BigDecimalSerializer(Fury fury) {
      super(fury, BigDecimal.class);
    }

    @Override
    public void write(MemoryBuffer buffer, BigDecimal value) {
      final byte[] bytes = value.unscaledValue().toByteArray();
      Preconditions.checkArgument(bytes.length <= 16);
      buffer.writeByte((byte) value.scale());
      buffer.writeByte((byte) bytes.length);
      buffer.writeBytes(bytes);
    }

    @Override
    public BigDecimal read(MemoryBuffer buffer) {
      int scale = buffer.readByte();
      int len = buffer.readByte();
      byte[] bytes = buffer.readBytes(len);
      final BigInteger bigInteger = new BigInteger(bytes);
      return new BigDecimal(bigInteger, scale);
    }
  }

  public static final class BigIntegerSerializer extends Serializer<BigInteger> {
    public BigIntegerSerializer(Fury fury) {
      super(fury, BigInteger.class);
    }

    @Override
    public void write(MemoryBuffer buffer, BigInteger value) {
      final byte[] bytes = value.toByteArray();
      Preconditions.checkArgument(bytes.length <= 16);
      buffer.writeByte((byte) bytes.length);
      buffer.writeBytes(bytes);
    }

    @Override
    public BigInteger read(MemoryBuffer buffer) {
      int len = buffer.readByte();
      byte[] bytes = buffer.readBytes(len);
      return new BigInteger(bytes);
    }
  }

  public static final class AtomicBooleanSerializer extends Serializer<AtomicBoolean> {

    public AtomicBooleanSerializer(Fury fury) {
      super(fury, AtomicBoolean.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicBoolean value) {
      buffer.writeBoolean(value.get());
    }

    @Override
    public AtomicBoolean read(MemoryBuffer buffer) {
      return new AtomicBoolean(buffer.readBoolean());
    }
  }

  public static final class AtomicIntegerSerializer extends Serializer<AtomicInteger> {

    public AtomicIntegerSerializer(Fury fury) {
      super(fury, AtomicInteger.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicInteger value) {
      buffer.writeInt(value.get());
    }

    @Override
    public AtomicInteger read(MemoryBuffer buffer) {
      return new AtomicInteger(buffer.readInt());
    }
  }

  public static final class AtomicLongSerializer extends Serializer<AtomicLong> {

    public AtomicLongSerializer(Fury fury) {
      super(fury, AtomicLong.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicLong value) {
      buffer.writeLong(value.get());
    }

    @Override
    public AtomicLong read(MemoryBuffer buffer) {
      return new AtomicLong(buffer.readLong());
    }
  }

  public static final class AtomicReferenceSerializer extends Serializer<AtomicReference> {

    public AtomicReferenceSerializer(Fury fury) {
      super(fury, AtomicReference.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicReference value) {
      fury.writeRef(buffer, value.get());
    }

    @Override
    public AtomicReference read(MemoryBuffer buffer) {
      return new AtomicReference(fury.readRef(buffer));
    }
  }

  public static final class CurrencySerializer extends Serializer<Currency> {
    public CurrencySerializer(Fury fury) {
      super(fury, Currency.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Currency object) {
      fury.writeJavaString(buffer, object.getCurrencyCode());
    }

    @Override
    public Currency read(MemoryBuffer buffer) {
      String currencyCode = fury.readJavaString(buffer);
      return Currency.getInstance(currencyCode);
    }
  }

  /** Serializer for {@link Charset}. */
  public static final class CharsetSerializer<T extends Charset> extends Serializer<T> {
    public CharsetSerializer(Fury fury, Class<T> type) {
      super(fury, type);
    }

    public void write(MemoryBuffer buffer, T object) {
      fury.writeJavaString(buffer, object.name());
    }

    public T read(MemoryBuffer buffer) {
      return (T) Charset.forName(fury.readJavaString(buffer));
    }
  }

  public static final class URISerializer extends Serializer<java.net.URI> {

    public URISerializer(Fury fury) {
      super(fury, URI.class);
    }

    @Override
    public void write(MemoryBuffer buffer, final URI uri) {
      fury.writeString(buffer, uri.toString());
    }

    @Override
    public URI read(MemoryBuffer buffer) {
      return URI.create(fury.readString(buffer));
    }
  }

  public static final class RegexSerializer extends Serializer<Pattern> {
    public RegexSerializer(Fury fury) {
      super(fury, Pattern.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Pattern pattern) {
      fury.writeJavaString(buffer, pattern.pattern());
      buffer.writeInt(pattern.flags());
    }

    @Override
    public Pattern read(MemoryBuffer buffer) {
      String regex = fury.readJavaString(buffer);
      int flags = buffer.readInt();
      return Pattern.compile(regex, flags);
    }
  }

  public static final class UUIDSerializer extends Serializer<UUID> {

    public UUIDSerializer(Fury fury) {
      super(fury, UUID.class);
    }

    @Override
    public void write(MemoryBuffer buffer, final UUID uuid) {
      buffer.writeLong(uuid.getMostSignificantBits());
      buffer.writeLong(uuid.getLeastSignificantBits());
    }

    @Override
    public UUID read(MemoryBuffer buffer) {
      return new UUID(buffer.readLong(), buffer.readLong());
    }
  }

  public static final class ClassSerializer extends Serializer<Class> {
    private static final byte USE_CLASS_ID = 0;
    private static final byte USE_CLASSNAME = 1;
    private static final byte PRIMITIVE_FLAG = 2;
    private final IdentityHashMap<Class<?>, Byte> primitivesMap = new IdentityHashMap<>();
    private final Class<?>[] id2PrimitiveClasses = new Class[Primitives.allPrimitiveTypes().size()];

    public ClassSerializer(Fury fury) {
      super(fury, Class.class);
      byte count = 0;
      for (Class<?> primitiveType : Primitives.allPrimitiveTypes()) {
        primitivesMap.put(primitiveType, count);
        id2PrimitiveClasses[count] = primitiveType;
        count++;
      }
    }

    @Override
    public void write(MemoryBuffer buffer, Class value) {
      fury.getClassResolver().writeClassInternal(buffer, value);
    }

    @Override
    public Class read(MemoryBuffer buffer) {
      return fury.getClassResolver().readClassInternal(buffer);
    }
  }

  /**
   * Serializer for empty object of type {@link Object}. Fury disabled serialization for jdk
   * internal types which doesn't implement {@link java.io.Serializable} for security, but empty
   * object is safe and used sometimes, so fury should support its serialization without disable
   * serializable or class registration checks.
   */
  // Use a separate serializer to avoid codegen for emtpy object.
  public static final class EmptyObjectSerializer extends Serializer<Object> {

    public EmptyObjectSerializer(Fury fury) {
      super(fury, Object.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {}

    @Override
    public Object read(MemoryBuffer buffer) {
      return new Object();
    }
  }

  public static void registerDefaultSerializers(Fury fury) {
    fury.registerSerializer(Class.class, new ClassSerializer(fury));
    fury.registerSerializer(StringBuilder.class, new StringBuilderSerializer(fury));
    fury.registerSerializer(StringBuffer.class, new StringBufferSerializer(fury));
    fury.registerSerializer(BigInteger.class, new BigIntegerSerializer(fury));
    fury.registerSerializer(BigDecimal.class, new BigDecimalSerializer(fury));
    fury.registerSerializer(AtomicBoolean.class, new AtomicBooleanSerializer(fury));
    fury.registerSerializer(AtomicInteger.class, new AtomicIntegerSerializer(fury));
    fury.registerSerializer(AtomicLong.class, new AtomicLongSerializer(fury));
    fury.registerSerializer(AtomicReference.class, new AtomicReferenceSerializer(fury));
    fury.registerSerializer(Currency.class, new CurrencySerializer(fury));
    fury.registerSerializer(URI.class, new URISerializer(fury));
    fury.registerSerializer(Pattern.class, new RegexSerializer(fury));
    fury.registerSerializer(UUID.class, new UUIDSerializer(fury));
    fury.registerSerializer(Object.class, new EmptyObjectSerializer(fury));
  }
}
