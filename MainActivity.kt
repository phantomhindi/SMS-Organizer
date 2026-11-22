package com.binay.smsorganizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class Cat(val label: String) {
    ALL("All"), PERSONAL("Personal"), OTP("OTP"), MONEY("Transactions"),
    BILLS("Bills"), PROMO("Promotions"), OTHER("Other")
}

data class Sms(
    val id: Long, val address: String, val body: String, val date: Long,
    val cat: Cat, val amount: Double?, val debit: Boolean
)

private val otpRx = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|security code)\\b")
private val moneyRx = Regex("(?i)(debited|credited|withdrawn|spent|paid|received|upi|txn|a/c|acct)")
private val debitRx = Regex("(?i)(debited|withdrawn|spent|paid)")
private val billRx = Regex("(?i)(bill|due date|due on|due by|recharge|premium|statement|payment due)")
private val promoRx = Regex("(?i)(offer|sale|discount|% ?off|cashback|coupon|limited time|t&c|http|www\\.)")
private val amountRx = Regex("(?i)(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d+)?)")
private val phoneRx = Regex("^\\+?[\\d\\s-]{8,}$")

fun classify(id: Long, address: String, body: String, date: Long): Sms {
    val amount = amountRx.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    val cat = when {
        otpRx.containsMatchIn(body) -> Cat.OTP
        moneyRx.containsMatchIn(body) && amount != null -> Cat.MONEY
        billRx.containsMatchIn(body) -> Cat.BILLS
        promoRx.containsMatchIn(body) -> Cat.PROMO
        phoneRx.matches(address) -> Cat.PERSONAL
        else -> Cat.OTHER
    }
    return Sms(id, address, body, date, cat, amount, debitRx.containsMatchIn(body))
}

fun loadSms(ctx: Context): List<Sms> {
    val out = ArrayList<Sms>()
    ctx.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf("_id", "address", "body", "date"), null, null, "date DESC"
    )?.use { c ->
        while (c.moveToNext() && out.size < 5000) {
            out.add(classify(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
        }
    }
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("SMS Organizer", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Needs permission to read your messages. Everything stays on your device.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow") }
        }
        return
    }

    var all by remember { mutableStateOf<List<Sms>>(emptyList()) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { loadSms(ctx) } }
    var cat by remember { mutableStateOf(Cat.ALL) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Long?>(null) }
    val shown = remember(all, cat, q) {
        all.filter {
            (cat == Cat.ALL || it.cat == cat) &&
                (q.isBlank() || it.body.contains(q, true) || it.address.contains(q, true))
        }
    }
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
        Text("SMS Organizer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            label = { Text("Search messages") }, modifier = Modifier.fillMaxWidth()
        )
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Cat.values().toList()) { c ->
                val n = if (c == Cat.ALL) all.size else all.count { it.cat == c }
                FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text("${c.label} $n") })
            }
        }
        if (cat == Cat.MONEY) {
            val spent = shown.filter { it.debit }.sumOf { it.amount ?: 0.0 }
            val got = shown.filter { !it.debit }.sumOf { it.amount ?: 0.0 }
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Spent: ₹%,.2f".format(spent), style = MaterialTheme.typography.titleMedium)
                    Text("Received: ₹%,.2f".format(got))
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth().clickable { open = if (open == m.id) null else m.id }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m.address, style = MaterialTheme.typography.titleSmall)
                            Text(df.format(Date(m.date)), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            m.body, maxLines = if (open == m.id) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium
                        )
                        Text(m.cat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
