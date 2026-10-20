package com.hasaan.busymode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Reads names and numbers from the phone's address book (only with the user's permission). */
object ContactsReader {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Returns [{ n: name, p: number }], or an empty list without permission. */
    fun read(context: Context, max: Int = 5000): JSONArray {
        val out = JSONArray()
        if (!hasPermission(context)) return out
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNum = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.length() < max) {
                    val name = c.getString(iName)?.trim().orEmpty()
                    val num = c.getString(iNum)?.trim().orEmpty()
                    if (name.isEmpty() || num.isEmpty()) continue
                    val key = name.lowercase() + "|" + num.filter { it.isDigit() }.takeLast(10)
                    if (!seen.add(key)) continue
                    out.put(JSONObject().put("n", name).put("p", num))
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
