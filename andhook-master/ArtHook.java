package apk.andhook;

import android.os.Build;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @auother weishu
 * @date 17/3/6
 * @update 17/6/10
 */
public class ArtHook {
	private static final String TAG = "ArtHook";
	private static Map<Pair<String, String>, Method> sBackups = new ConcurrentHashMap<>();

	public static void hookNoBackup(final Method origin, final Method replace) {
		// replace method
		Memory.memcpy(MethodInspect.getMethodAddress(origin),
				MethodInspect.getMethodAddress(replace),
				MethodInspect.getArtMethodSize());
	}

	public static void hook(Method origin, Method replace) {
		// 1. backup
		final Method backUp = backUp(origin, replace);
		// @TODO Overload method is not supported
		sBackups.put(
				Pair.create(replace.getDeclaringClass().getName(),
						replace.getName()), backUp);

		// 2. replace method
		hookNoBackup(origin, replace);
	}

	public static Object callOrigin(Object receiver, Object... params) {
		final StackTraceElement currentStack = Thread.currentThread()
				.getStackTrace()[4];
		final Method method = sBackups.get(Pair.create(
				currentStack.getClassName(), currentStack.getMethodName()));

		try {
			return method.invoke(receiver, params);
		} catch (Throwable e) {
			throw new UnsupportedException("invoke origin method error", e);
		}
	}

	private static Method backUp(Method origin, Method replace) {
		try {
			if (Build.VERSION.SDK_INT < 23) {
				final Class<?> artMethodClass = Class
						.forName("java.lang.reflect.ArtMethod");
				final Field accessFlagsField = artMethodClass
						.getDeclaredField("accessFlags");
				accessFlagsField.setAccessible(true);

				final Constructor<?> artMethodConstructor = artMethodClass
						.getDeclaredConstructor();
				artMethodConstructor.setAccessible(true);

				// new Method(ArtMethod artMethod)
				final Object newArtMethod = artMethodConstructor.newInstance();
				final Constructor<Method> methodConstructor = Method.class
						.getDeclaredConstructor(artMethodClass);
				final Method newMethod = methodConstructor
						.newInstance(newArtMethod);
				newMethod.setAccessible(true);

				Memory.memcpy(MethodInspect.getMethodAddress(newMethod),
						MethodInspect.getMethodAddress(origin),
						MethodInspect.getArtMethodSize());

				accessFlagsField.set(newArtMethod,
						(Integer) accessFlagsField.get(newArtMethod)
								& (~Modifier.PUBLIC) | Modifier.PRIVATE);
				return newMethod;
			} else {
				// AbstractMethod
				final Class<?> abstractMethodClass = Method.class
						.getSuperclass();
				final Field accessFlagsField = abstractMethodClass
						.getDeclaredField("accessFlags");
				accessFlagsField.setAccessible(true);
				final Field artMethodField = abstractMethodClass
						.getDeclaredField("artMethod");
				artMethodField.setAccessible(true);

				// make the construct accessible, we can not just use
				// `setAccessible`
				final Constructor<Method> methodConstructor = Method.class
						.getDeclaredConstructor();
				final Field override = AccessibleObject.class
						.getDeclaredField(Build.VERSION.SDK_INT == 23/*
																	 * Build.
																	 * VERSION_CODES
																	 * .M
																	 */? "flag"
								: "override");
				override.setAccessible(true);
				override.set(methodConstructor, true);

				// clone the origin method
				final Method newMethod = methodConstructor.newInstance();
				newMethod.setAccessible(true);
				for (Field field : abstractMethodClass.getDeclaredFields()) {
					field.setAccessible(true);
					field.set(newMethod, field.get(origin));
				}

				// allocate new artMethod struct, we can not use memory managed
				// by JVM
				final int artMethodSize = (int) MethodInspect
						.getArtMethodSize();
				final ByteBuffer artMethod = ByteBuffer
						.allocateDirect(artMethodSize);
				Long artMethodAddress;
				int ACC_FLAG_OFFSET;
				if (Build.VERSION.SDK_INT < 24) {
					// Below Android N, the jdk implementation is not openjdk
					artMethodAddress = (Long) Reflection.get(Buffer.class,
							null, "effectiveDirectAddress", artMethod);
					// http://androidxref.com/6.0.0_r1/xref/art/runtime/art_method.h
					// GCRoot * 3, sizeof(GCRoot) =
					// sizeof(mirror::CompressedReference) =
					// sizeof(mirror::ObjectReference) = sizeof(uint32_t) = 4
					ACC_FLAG_OFFSET = 12;
				} else {
					artMethodAddress = (Long) Reflection.call(
							artMethod.getClass(), null, "address", artMethod,
							null, null);
					// http://androidxref.com/7.0.0_r1/xref/art/runtime/art_method.h
					// sizeof(GCRoot) = 4
					ACC_FLAG_OFFSET = 4;
				}
				Memory.memcpy(artMethodAddress,
						MethodInspect.getMethodAddress(origin), artMethodSize);

				final byte[] newMethodBytes = new byte[artMethodSize];
				artMethod.get(newMethodBytes);
				Log.d(TAG, "new: " + Arrays.toString(newMethodBytes));
				Log.i(TAG,
						"origin:"
								+ Arrays.toString(MethodInspect
										.getMethodBytes(origin)));

				// replace the artMethod of our new method
				artMethodField.set(newMethod, artMethodAddress);

				// modify the access flag of the new method
				final Integer accessFlags = (Integer) accessFlagsField
						.get(origin);
				Log.d(TAG, "Acc:" + accessFlags);
				final int privateAccFlag = accessFlags & ~Modifier.PUBLIC
						| Modifier.PRIVATE;
				accessFlagsField.set(newMethod, privateAccFlag);

				// 1. try big endian
				artMethod.order(ByteOrder.BIG_ENDIAN);
				int nativeAccFlags = artMethod.getInt(ACC_FLAG_OFFSET);
				Log.d(TAG, "bitendian:" + nativeAccFlags);
				if (nativeAccFlags == accessFlags) {
					// hit!
					artMethod.putInt(ACC_FLAG_OFFSET, privateAccFlag);
				} else {
					// 2. try little endian
					artMethod.order(ByteOrder.LITTLE_ENDIAN);
					nativeAccFlags = artMethod.getInt(ACC_FLAG_OFFSET);
					Log.d(TAG, "littleendian:" + nativeAccFlags);
					if (nativeAccFlags == accessFlags) {
						artMethod.putInt(ACC_FLAG_OFFSET, privateAccFlag);
					} else {
						// the offset is error!
						throw new RuntimeException(
								"native set access flags error!");
					}
				}

				return newMethod;
			}
		} catch (Throwable e) {
			throw new UnsupportedException("can not backup method", e);
		}
	}

	private static class Reflection {
		public static Object call(Class<?> clazz, String className,
				String methodName, Object receiver, Class<?>[] types,
				Object[] params) throws UnsupportedException {
			try {
				if (clazz == null)
					clazz = Class.forName(className);
				final Method method = clazz
						.getDeclaredMethod(methodName, types);
				method.setAccessible(true);
				return method.invoke(receiver, params);
			} catch (Throwable throwable) {
				throw new UnsupportedException("reflection error:", throwable);
			}
		}

		public static Object get(Class<?> clazz, String className,
				String fieldName, Object receiver) {
			try {
				if (clazz == null)
					clazz = Class.forName(className);
				final Field field = clazz.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(receiver);
			} catch (Throwable e) {
				throw new UnsupportedException("reflection error:", e);
			}
		}
	}

	public static class MethodInspect {

		static long sMethodSize = -1;

		public static void ruler1() {
		}

		public static void ruler2() {
		}

		public static long getMethodAddress(Method method) {
			final Object mirrorMethod = Reflection.get(
					Method.class.getSuperclass(), null, "artMethod", method);
			if (mirrorMethod.getClass().equals(Long.class)) {
				return (Long) mirrorMethod;
			}
			return Unsafe.getObjectAddress(mirrorMethod);
		}

		public static long getArtMethodSize() {
			if (sMethodSize <= 0) {
				try {
					final Method f1 = MethodInspect.class
							.getDeclaredMethod("ruler1");
					final Method f2 = MethodInspect.class
							.getDeclaredMethod("ruler2");
					sMethodSize = getMethodAddress(f2) - getMethodAddress(f1);
					return sMethodSize;
				} catch (Exception e) {
					throw new RuntimeException(
							"exceuse me ?? can not found method??");
				}
			}
			return sMethodSize;
		}

		public static byte[] getMethodBytes(Method method) {
			if (method == null) {
				return null;
			}
			final byte[] ret = new byte[(int) getArtMethodSize()];
			final long baseAddr = getMethodAddress(method);
			for (int i = 0; i < ret.length; ++i) {
				ret[i] = Memory.peekByte(baseAddr + i);
			}
			return ret;
		}
	}

	private static class Memory {

		// libcode.io.Memory#peekByte
		static byte peekByte(long address) {
			return (Byte) Reflection.call(null, "libcore.io.Memory",
					"peekByte", null, new Class[] { long.class },
					new Object[] { address });
		}

		static void pokeByte(long address, byte value) {
			Reflection.call(null, "libcore.io.Memory", "pokeByte", null,
					new Class[] { long.class, byte.class }, new Object[] {
							address, value });
		}

		public static void memcpy(long dst, long src, long length) {
			for (long i = 0; i < length; ++i) {
				pokeByte(dst, peekByte(src));
				++dst;
				++src;
			}
		}
	}

	static class Unsafe {
		static final String UNSAFE_CLASS = "sun.misc.Unsafe";
		static Object THE_UNSAFE = Reflection.get(null, UNSAFE_CLASS,
				"THE_ONE", null);

		public static long getObjectAddress(Object o) {
			final Object[] objects = { o };
			final Integer baseOffset = (Integer) Reflection.call(null,
					UNSAFE_CLASS, "arrayBaseOffset", THE_UNSAFE,
					new Class[] { Class.class },
					new Object[] { Object[].class });
			return ((Number) Reflection.call(null, UNSAFE_CLASS, "getInt",
					THE_UNSAFE, new Class[] { Object.class, long.class },
					new Object[] { objects, baseOffset.longValue() }))
					.longValue();
		}
	}

	private static class UnsupportedException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UnsupportedException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}