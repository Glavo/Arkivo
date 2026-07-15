# Java Code Style Requirements

These rules apply to all Java code written or modified in this repository.

## Nullability

- Annotate every class with JetBrains Annotations `@NotNullByDefault`.
- Any type, field, parameter, return value, local variable, or generic type argument that may be `null` must be explicitly annotated with `@Nullable`.
- Nullability must never be implicit.

## Optional Values

- Do not use Java `Optional`.
- Represent optional or absent object values with `@Nullable`.
- Represent optional or absent numeric values with primitive numeric types and named negative sentinel constants when the
  valid value domain is non-negative, for example `UNKNOWN_SIZE = -1L`.
- Do not use boxed numeric values such as `@Nullable Long` for optional numeric values when a primitive sentinel can
  represent absence.
- Do not introduce APIs that require callers to unwrap `Optional`.

## Java Types

- Use Java `record` types when they fit the data model.

## Immutability Annotations

- Annotate immutable collections and arrays with JetBrains Annotations `@Unmodifiable`.
- Annotate immutable collection views with JetBrains Annotations `@UnmodifiableView`.
- Annotate immutable NIO buffers such as `ByteBuffer`, `IntBuffer`, `LongBuffer`, and other `Buffer`
  subclasses with `@Unmodifiable`.
- Annotate read-only or immutable views of NIO buffers with `@UnmodifiableView`.
- For arrays, place the annotation on the array dimension, for example `String @Unmodifiable []`.
- For multidimensional immutable arrays, annotate every immutable dimension, for example
  `int @Unmodifiable [] @Unmodifiable []`.

## Documentation

- Every class, field, and method must have documentation.
- Documentation must use `///` Markdown-style Javadoc comments.
- Documentation for Java record components must be written in the record class Javadoc with `@param` entries, not as inline comments before individual components.
- Keep documentation accurate and specific to the actual behavior, constraints, and side effects.
- Add concise implementation comments inside complex logic whenever they materially improve readability or explain non-obvious behavior.
