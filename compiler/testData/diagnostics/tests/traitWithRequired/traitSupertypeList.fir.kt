open class bar()

interface <!CONSTRUCTOR_IN_INTERFACE!>Foo()<!> : <!INTERFACE_WITH_SUPERCLASS, SUPERTYPE_INITIALIZED_IN_INTERFACE!>bar<!>(), <!MANY_CLASSES_IN_SUPERTYPE_LIST, SUPERTYPE_APPEARS_TWICE!>bar<!>, <!MANY_CLASSES_IN_SUPERTYPE_LIST, SUPERTYPE_APPEARS_TWICE!>bar<!> {
}

interface Foo2 : <!INTERFACE_WITH_SUPERCLASS!>bar<!>, Foo {
}

open class Foo1() : bar(), <!MANY_CLASSES_IN_SUPERTYPE_LIST, SUPERTYPE_APPEARS_TWICE!>bar<!>, Foo, <!SUPERTYPE_APPEARS_TWICE, UNRESOLVED_REFERENCE!>Foo<!>() {}
open class Foo12 : bar(), <!MANY_CLASSES_IN_SUPERTYPE_LIST, SUPERTYPE_APPEARS_TWICE!>bar<!> {}
