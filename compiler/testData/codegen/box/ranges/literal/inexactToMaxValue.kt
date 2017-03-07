// TODO: muted automatically, investigate should it be ran for JS or not
// IGNORE_BACKEND: JS, NATIVE

// Auto-generated by org.jetbrains.kotlin.generators.tests.GenerateRangesCodegenTestData. DO NOT EDIT!
// WITH_RUNTIME


import java.lang.Integer.MAX_VALUE as MaxI
import java.lang.Integer.MIN_VALUE as MinI
import java.lang.Byte.MAX_VALUE as MaxB
import java.lang.Byte.MIN_VALUE as MinB
import java.lang.Short.MAX_VALUE as MaxS
import java.lang.Short.MIN_VALUE as MinS
import java.lang.Long.MAX_VALUE as MaxL
import java.lang.Long.MIN_VALUE as MinL
import java.lang.Character.MAX_VALUE as MaxC
import java.lang.Character.MIN_VALUE as MinC

fun box(): String {
    val list1 = ArrayList<Int>()
    for (i in (MaxI - 5)..MaxI step 3) {
        list1.add(i)
        if (list1.size > 23) break
    }
    if (list1 != listOf<Int>(MaxI - 5, MaxI - 2)) {
        return "Wrong elements for (MaxI - 5)..MaxI step 3: $list1"
    }

    val list2 = ArrayList<Int>()
    for (i in (MaxB - 5).toByte()..MaxB step 3) {
        list2.add(i)
        if (list2.size > 23) break
    }
    if (list2 != listOf<Int>((MaxB - 5).toInt(), (MaxB - 2).toInt())) {
        return "Wrong elements for (MaxB - 5).toByte()..MaxB step 3: $list2"
    }

    val list3 = ArrayList<Int>()
    for (i in (MaxS - 5).toShort()..MaxS step 3) {
        list3.add(i)
        if (list3.size > 23) break
    }
    if (list3 != listOf<Int>((MaxS - 5).toInt(), (MaxS - 2).toInt())) {
        return "Wrong elements for (MaxS - 5).toShort()..MaxS step 3: $list3"
    }

    val list4 = ArrayList<Long>()
    for (i in (MaxL - 5).toLong()..MaxL step 3) {
        list4.add(i)
        if (list4.size > 23) break
    }
    if (list4 != listOf<Long>((MaxL - 5).toLong(), (MaxL - 2).toLong())) {
        return "Wrong elements for (MaxL - 5).toLong()..MaxL step 3: $list4"
    }

    val list5 = ArrayList<Char>()
    for (i in (MaxC - 5)..MaxC step 3) {
        list5.add(i)
        if (list5.size > 23) break
    }
    if (list5 != listOf<Char>((MaxC - 5), (MaxC - 2))) {
        return "Wrong elements for (MaxC - 5)..MaxC step 3: $list5"
    }

    return "OK"
}
