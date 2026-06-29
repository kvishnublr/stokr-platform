package com.stokr.external;

import com.stokr.engine.CandleData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaCandleService {

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String zerodhaApiSecret;

    private static final String KITE_API_BASE = "https://api.kite.trade";
    private static final Map<String, String> SYMBOL_TO_TOKEN = new HashMap<>();

    static {
        // NIFTY 50
        SYMBOL_TO_TOKEN.put("RELIANCE",   "738561");
        SYMBOL_TO_TOKEN.put("TCS",        "2953217");
        SYMBOL_TO_TOKEN.put("HDFCBANK",   "341249");
        SYMBOL_TO_TOKEN.put("ICICIBANK",  "1270529");
        SYMBOL_TO_TOKEN.put("INFY",       "408065");
        SYMBOL_TO_TOKEN.put("HINDUNILVR", "356865");
        SYMBOL_TO_TOKEN.put("ITC",        "424961");
        SYMBOL_TO_TOKEN.put("KOTAKBANK",  "492033");
        SYMBOL_TO_TOKEN.put("LT",         "2939649");
        SYMBOL_TO_TOKEN.put("SBIN",       "779521");
        SYMBOL_TO_TOKEN.put("AXISBANK",   "1510401");
        SYMBOL_TO_TOKEN.put("BAJFINANCE", "81153");
        SYMBOL_TO_TOKEN.put("BHARTIARTL", "2714625");
        SYMBOL_TO_TOKEN.put("TITAN",      "897537");
        SYMBOL_TO_TOKEN.put("MARUTI",     "2815745");
        SYMBOL_TO_TOKEN.put("HCLTECH",    "1850625");
        SYMBOL_TO_TOKEN.put("SUNPHARMA",  "857857");
        SYMBOL_TO_TOKEN.put("TATAMOTORS", "884737");
        SYMBOL_TO_TOKEN.put("NTPC",       "2977281");
        SYMBOL_TO_TOKEN.put("BAJAJFINSV", "54273");
        SYMBOL_TO_TOKEN.put("WIPRO",      "969473");
        SYMBOL_TO_TOKEN.put("JSWSTEEL",   "3001089");
        SYMBOL_TO_TOKEN.put("ONGC",       "633601");
        SYMBOL_TO_TOKEN.put("POWERGRID",  "3834113");
        SYMBOL_TO_TOKEN.put("COALINDIA",  "5215745");
        SYMBOL_TO_TOKEN.put("GRASIM",     "315393");
        SYMBOL_TO_TOKEN.put("TATASTEEL",  "895745");
        SYMBOL_TO_TOKEN.put("BPCL",       "134657");
        SYMBOL_TO_TOKEN.put("HINDALCO",   "348929");
        SYMBOL_TO_TOKEN.put("ULTRACEMCO", "2952193");
        SYMBOL_TO_TOKEN.put("ADANIENT",   "6401");
        SYMBOL_TO_TOKEN.put("ADANIPORTS", "3861249");
        SYMBOL_TO_TOKEN.put("APOLLOHOSP", "40193");
        SYMBOL_TO_TOKEN.put("DIVISLAB",   "2800641");
        SYMBOL_TO_TOKEN.put("DRREDDY",    "225537");
        SYMBOL_TO_TOKEN.put("EICHERMOT",  "232961");
        SYMBOL_TO_TOKEN.put("HDFCLIFE",   "119553");
        SYMBOL_TO_TOKEN.put("HEROMOTOCO", "345089");
        SYMBOL_TO_TOKEN.put("INDUSINDBK", "1346049");
        SYMBOL_TO_TOKEN.put("M&M",        "519937");
        SYMBOL_TO_TOKEN.put("NESTLEIND",  "4598529");
        SYMBOL_TO_TOKEN.put("SBILIFE",    "5582849");
        SYMBOL_TO_TOKEN.put("TATACONSUM", "878593");
        SYMBOL_TO_TOKEN.put("TECHM",      "3465729");
        SYMBOL_TO_TOKEN.put("TRENT",      "502785");
        SYMBOL_TO_TOKEN.put("DMART",      "5097729");
        SYMBOL_TO_TOKEN.put("UPL",        "2889473");
        SYMBOL_TO_TOKEN.put("CIPLA",      "177665");
        SYMBOL_TO_TOKEN.put("BRITANNIA",  "140033");
        SYMBOL_TO_TOKEN.put("ASIANPAINT", "60417");
        // NIFTY Next 50
        SYMBOL_TO_TOKEN.put("BERGEPAINT", "103425");
        SYMBOL_TO_TOKEN.put("CANBK",      "2763265");
        SYMBOL_TO_TOKEN.put("DABUR",      "197633");
        SYMBOL_TO_TOKEN.put("GODREJCP",   "2585345");
        SYMBOL_TO_TOKEN.put("HAL",        "589569");
        SYMBOL_TO_TOKEN.put("HAVELLS",    "2513665");
        SYMBOL_TO_TOKEN.put("HDFCAMC",    "1086465");
        SYMBOL_TO_TOKEN.put("IOB",        "2393089");
        SYMBOL_TO_TOKEN.put("IRCTC",      "3484417");
        SYMBOL_TO_TOKEN.put("LICI",       "2426881");
        SYMBOL_TO_TOKEN.put("MCDOWELL",   "2674433");
        SYMBOL_TO_TOKEN.put("PIDILITIND", "681985");
        SYMBOL_TO_TOKEN.put("POLYCAB",    "2455041");
        SYMBOL_TO_TOKEN.put("SIEMENS",    "806401");
        SYMBOL_TO_TOKEN.put("ZOMATO",     "1304833");
        SYMBOL_TO_TOKEN.put("AMBUJACEM",  "325121");
        SYMBOL_TO_TOKEN.put("ATGL",       "1552897");
        SYMBOL_TO_TOKEN.put("BANDHANBNK", "579329");
        SYMBOL_TO_TOKEN.put("BANKBARODA", "1195009");
        SYMBOL_TO_TOKEN.put("BEL",        "98049");
        SYMBOL_TO_TOKEN.put("CHOLAFIN",   "175361");
        SYMBOL_TO_TOKEN.put("COFORGE",    "2955009");
        SYMBOL_TO_TOKEN.put("COLPAL",     "3876097");
        SYMBOL_TO_TOKEN.put("DALBHARAT",  "2067201");
        SYMBOL_TO_TOKEN.put("FEDERALBNK", "261889");
        SYMBOL_TO_TOKEN.put("GAIL",       "1207553");
        SYMBOL_TO_TOKEN.put("GODREJPROP", "4576001");
        SYMBOL_TO_TOKEN.put("IDFCFIRSTB", "2863105");
        SYMBOL_TO_TOKEN.put("INDUSTOWER", "7458561");
        SYMBOL_TO_TOKEN.put("IRFC",       "519425");
        SYMBOL_TO_TOKEN.put("JUBLFOOD",   "4632577");
        SYMBOL_TO_TOKEN.put("KALYANKJIL", "756481");
        SYMBOL_TO_TOKEN.put("LALPATHLAB", "2983425");
        SYMBOL_TO_TOKEN.put("LODHA",      "824321");
        SYMBOL_TO_TOKEN.put("LTTS",       "4752385");
        SYMBOL_TO_TOKEN.put("LUPIN",      "2672641");
        SYMBOL_TO_TOKEN.put("MFSL",       "548353");
        SYMBOL_TO_TOKEN.put("NHPC",       "4454401");
        SYMBOL_TO_TOKEN.put("NYKAA",      "1675521");
        SYMBOL_TO_TOKEN.put("OFSS",       "2748929");
        SYMBOL_TO_TOKEN.put("PAGEIND",    "3689729");
        SYMBOL_TO_TOKEN.put("PAYTM",      "1716481");
        SYMBOL_TO_TOKEN.put("PERSISTENT", "4701441");
        SYMBOL_TO_TOKEN.put("RECLTD",     "3930881");
        SYMBOL_TO_TOKEN.put("SAIL",       "758529");
        SYMBOL_TO_TOKEN.put("SHREECEM",   "794369");
        SYMBOL_TO_TOKEN.put("TORNTPHARM", "900609");
        SYMBOL_TO_TOKEN.put("TVSMOTOR",   "2170625");
        SYMBOL_TO_TOKEN.put("VBL",        "4843777");
        SYMBOL_TO_TOKEN.put("VEDL",       "784129");
        SYMBOL_TO_TOKEN.put("VOLTAS",     "951809");
        // Extended universe — Nifty 100/200/500 additions
        SYMBOL_TO_TOKEN.put("AARTIIND",   "1793");
        SYMBOL_TO_TOKEN.put("ABB",        "3329");
        SYMBOL_TO_TOKEN.put("ABCAPITAL",  "5533185");
        SYMBOL_TO_TOKEN.put("ABFRL",      "7707649");
        SYMBOL_TO_TOKEN.put("ACC",        "5633");
        SYMBOL_TO_TOKEN.put("ADANIGREEN", "912129");
        SYMBOL_TO_TOKEN.put("AJANTPHARM", "2079745");
        SYMBOL_TO_TOKEN.put("ALKEM",      "2995969");
        SYMBOL_TO_TOKEN.put("APARINDS",   "2941697");
        SYMBOL_TO_TOKEN.put("APLLTD",     "6483969");
        SYMBOL_TO_TOKEN.put("ASHOKLEY",   "54273");
        SYMBOL_TO_TOKEN.put("ASTRAL",     "3691009");
        SYMBOL_TO_TOKEN.put("ATUL",       "67329");
        SYMBOL_TO_TOKEN.put("AUBANK",     "5436929");
        SYMBOL_TO_TOKEN.put("AUROPHARMA", "70401");
        SYMBOL_TO_TOKEN.put("BAJAJ-AUTO", "4267265");
        SYMBOL_TO_TOKEN.put("BAJAJHLDNG", "78081");
        SYMBOL_TO_TOKEN.put("BALKRISHNA", "2606337");
        SYMBOL_TO_TOKEN.put("BATAINDIA",  "94977");
        SYMBOL_TO_TOKEN.put("BHARATFORG", "108033");
        SYMBOL_TO_TOKEN.put("BIOCON",     "2911489");
        SYMBOL_TO_TOKEN.put("BOSCHLTD",   "558337");
        SYMBOL_TO_TOKEN.put("CANFINHOME", "149249");
        SYMBOL_TO_TOKEN.put("CASTROLIND", "320001");
        SYMBOL_TO_TOKEN.put("CEATLTD",    "3905025");
        SYMBOL_TO_TOKEN.put("CONCOR",     "1215745");
        SYMBOL_TO_TOKEN.put("COROMANDEL", "189185");
        SYMBOL_TO_TOKEN.put("CROMPTON",   "4376065");
        SYMBOL_TO_TOKEN.put("CUMMINSIND", "486657");
        SYMBOL_TO_TOKEN.put("DEEPAKNTR",  "5105409");
        SYMBOL_TO_TOKEN.put("DIXON",      "5552641");
        SYMBOL_TO_TOKEN.put("DLF",        "3771393");
        SYMBOL_TO_TOKEN.put("EMAMILTD",   "3460353");
        SYMBOL_TO_TOKEN.put("ENDURANCE",  "4818433");
        SYMBOL_TO_TOKEN.put("EQUITASBNK", "233729");
        SYMBOL_TO_TOKEN.put("ESCORTS",    "245249");
        SYMBOL_TO_TOKEN.put("EXIDEIND",   "173057");
        SYMBOL_TO_TOKEN.put("FINCABLES",  "265729");
        SYMBOL_TO_TOKEN.put("GAIL",       "1207553");
        SYMBOL_TO_TOKEN.put("GMRAIRPORT", "3463169");
        SYMBOL_TO_TOKEN.put("GNFC",       "300545");
        SYMBOL_TO_TOKEN.put("GRANULES",   "3039233");
        SYMBOL_TO_TOKEN.put("HFCL",       "5619457");
        SYMBOL_TO_TOKEN.put("HINDPETRO",  "359937");
        SYMBOL_TO_TOKEN.put("HONAUT",     "874753");
        SYMBOL_TO_TOKEN.put("ICICIGI",    "5573121");
        SYMBOL_TO_TOKEN.put("ICICIPRULI", "4774913");
        SYMBOL_TO_TOKEN.put("IDEA",       "3677697");
        SYMBOL_TO_TOKEN.put("IGL",        "2883073");
        SYMBOL_TO_TOKEN.put("INDIAMART",  "2745857");
        SYMBOL_TO_TOKEN.put("INDIGO",     "2865921");
        SYMBOL_TO_TOKEN.put("IOC",        "415745");
        SYMBOL_TO_TOKEN.put("JINDALSTEL", "1723649");
        SYMBOL_TO_TOKEN.put("JKCEMENT",   "3397121");
        SYMBOL_TO_TOKEN.put("JSL",        "2876417");
        SYMBOL_TO_TOKEN.put("JSWENERGY",  "4574465");
        SYMBOL_TO_TOKEN.put("JUBLINGREA", "712449");
        SYMBOL_TO_TOKEN.put("KAJARIACER", "462849");
        SYMBOL_TO_TOKEN.put("KPIL",       "464385");
        SYMBOL_TO_TOKEN.put("LATENTVIEW", "1745409");
        SYMBOL_TO_TOKEN.put("LICHSGFIN",  "511233");
        SYMBOL_TO_TOKEN.put("LINDEINDIA", "416513");
        SYMBOL_TO_TOKEN.put("LUPIN",      "2672641");
        SYMBOL_TO_TOKEN.put("MARICO",     "1041153");
        SYMBOL_TO_TOKEN.put("MAXHEALTH",  "5728513");
        SYMBOL_TO_TOKEN.put("METROPOLIS", "2452737");
        SYMBOL_TO_TOKEN.put("MGL",        "4488705");
        SYMBOL_TO_TOKEN.put("MOTHERSON",  "1076225");
        SYMBOL_TO_TOKEN.put("MPHASIS",    "1152769");
        SYMBOL_TO_TOKEN.put("MUTHOOTFIN", "6054401");
        SYMBOL_TO_TOKEN.put("NAUKRI",     "3520257");
        SYMBOL_TO_TOKEN.put("NLCINDIA",   "2197761");
        SYMBOL_TO_TOKEN.put("NMDC",       "3924993");
        SYMBOL_TO_TOKEN.put("NOCIL",      "625153");
        SYMBOL_TO_TOKEN.put("OBEROIRLTY", "5181953");
        SYMBOL_TO_TOKEN.put("PETRONET",   "2905857");
        SYMBOL_TO_TOKEN.put("PFC",        "3660545");
        SYMBOL_TO_TOKEN.put("PHOENIXLTD", "3725313");
        SYMBOL_TO_TOKEN.put("PIIND",      "6191105");
        SYMBOL_TO_TOKEN.put("PNB",        "2730497");
        SYMBOL_TO_TOKEN.put("PRESTIGE",   "5197313");
        SYMBOL_TO_TOKEN.put("PVRINOX",    "3365633");
        SYMBOL_TO_TOKEN.put("RADICO",     "2813441");
        SYMBOL_TO_TOKEN.put("RAMCOCEM",   "523009");
        SYMBOL_TO_TOKEN.put("RBLBANK",    "4708097");
        SYMBOL_TO_TOKEN.put("RELAXO",     "6201601");
        SYMBOL_TO_TOKEN.put("ROUTE",      "32769");
        SYMBOL_TO_TOKEN.put("SANOFI",     "369153");
        SYMBOL_TO_TOKEN.put("SCHAEFFLER", "258817");
        SYMBOL_TO_TOKEN.put("SHRIRAMFIN", "1102337");
        SYMBOL_TO_TOKEN.put("SJVN",       "4834049");
        SYMBOL_TO_TOKEN.put("SKFINDIA",   "815617");
        SYMBOL_TO_TOKEN.put("SOBHA",      "3539457");
        SYMBOL_TO_TOKEN.put("SRF",        "837889");
        SYMBOL_TO_TOKEN.put("STARHEALTH", "1813249");
        SYMBOL_TO_TOKEN.put("SUNDARMFIN", "854785");
        SYMBOL_TO_TOKEN.put("SUNDRMFAST", "856321");
        SYMBOL_TO_TOKEN.put("SUPREMEIND", "860929");
        SYMBOL_TO_TOKEN.put("SYNGENE",    "2622209");
        SYMBOL_TO_TOKEN.put("TATACHEM",   "871681");
        SYMBOL_TO_TOKEN.put("TATAPOWER",  "877057");
        SYMBOL_TO_TOKEN.put("TEAMLEASE",  "3255297");
        SYMBOL_TO_TOKEN.put("TIINDIA",    "79873");
        SYMBOL_TO_TOKEN.put("TATAELXSI", "873217");
        SYMBOL_TO_TOKEN.put("JIOFIN",    "4644609");
        SYMBOL_TO_TOKEN.put("TIMKEN",     "3634689");
        SYMBOL_TO_TOKEN.put("TTKPRESTIG", "907777");
        SYMBOL_TO_TOKEN.put("UNIONBANK",  "2752769");
        SYMBOL_TO_TOKEN.put("UNITDSPR",   "2674433");
        SYMBOL_TO_TOKEN.put("YESBANK",    "3050241");
        SYMBOL_TO_TOKEN.put("ZYDUSLIFE",  "2029825");
        SYMBOL_TO_TOKEN.put("COFORGE",    "2955009");
        SYMBOL_TO_TOKEN.put("COLPAL",     "3876097");
        // Extended — NIFTY 500 additions (instrument tokens from Zerodha instruments CSV)
        SYMBOL_TO_TOKEN.put("AARTIDRUGS", "1147137");
        SYMBOL_TO_TOKEN.put("AFFLE",      "2903809");
        SYMBOL_TO_TOKEN.put("AIAENG",     "3350017");
        SYMBOL_TO_TOKEN.put("ALKYLAMINE", "1148673");
        SYMBOL_TO_TOKEN.put("AMBER",      "303361");
        SYMBOL_TO_TOKEN.put("ANGELONE",   "82945");
        SYMBOL_TO_TOKEN.put("APOLLOTYRE", "41729");
        SYMBOL_TO_TOKEN.put("ARVIND",     "49409");
        SYMBOL_TO_TOKEN.put("ASAHIINDIA", "1376769");
        SYMBOL_TO_TOKEN.put("AVANTIFEED", "2031617");
        SYMBOL_TO_TOKEN.put("BAJAJCON",   "4999937");
        SYMBOL_TO_TOKEN.put("BALMLAWRIE", "86529");
        SYMBOL_TO_TOKEN.put("BALRAMCHIN", "87297");
        SYMBOL_TO_TOKEN.put("BANKINDIA",  "1214721");
        SYMBOL_TO_TOKEN.put("BASF",       "94209");
        SYMBOL_TO_TOKEN.put("BAYERCROP",  "4589313");
        SYMBOL_TO_TOKEN.put("BBTC",       "97281");
        SYMBOL_TO_TOKEN.put("BEML",       "101121");
        SYMBOL_TO_TOKEN.put("BHEL",       "112129");
        SYMBOL_TO_TOKEN.put("BIKAJI",     "3063297");
        SYMBOL_TO_TOKEN.put("BLS",        "4423425");
        SYMBOL_TO_TOKEN.put("BLUESTARCO", "2127617");
        SYMBOL_TO_TOKEN.put("BRIGADE",    "3887105");
        SYMBOL_TO_TOKEN.put("BSOFT",      "1790465");
        SYMBOL_TO_TOKEN.put("CAMPUS",     "2396673");
        SYMBOL_TO_TOKEN.put("CAMS",       "87553");
        SYMBOL_TO_TOKEN.put("CAPLIPOINT", "999937");
        SYMBOL_TO_TOKEN.put("CARTRADE",   "1384193");
        SYMBOL_TO_TOKEN.put("CARYSIL",    "481025");
        SYMBOL_TO_TOKEN.put("CDSL",       "5420545");
        SYMBOL_TO_TOKEN.put("CENTURYPLY", "3406081");
        SYMBOL_TO_TOKEN.put("CERA",       "3849985");
        SYMBOL_TO_TOKEN.put("CESC",       "160769");
        SYMBOL_TO_TOKEN.put("CHALET",     "2187777");
        SYMBOL_TO_TOKEN.put("CHAMBLFERT", "163073");
        SYMBOL_TO_TOKEN.put("CHEMCON",    "69121");
        SYMBOL_TO_TOKEN.put("CLEAN",      "1292545");
        SYMBOL_TO_TOKEN.put("COCHINSHIP", "5506049");
        SYMBOL_TO_TOKEN.put("CRAFTSMAN",  "730625");
        SYMBOL_TO_TOKEN.put("CREDITACC",  "1131777");
        SYMBOL_TO_TOKEN.put("CYIENT",     "1471489");
        SYMBOL_TO_TOKEN.put("DATAMATICS", "2924289");
        SYMBOL_TO_TOKEN.put("DATAPATTNS", "1883649");
        SYMBOL_TO_TOKEN.put("DCBBANK",    "3513601");
        SYMBOL_TO_TOKEN.put("DCMSHRIRAM", "207617");
        SYMBOL_TO_TOKEN.put("DEEPAKFERT", "211713");
        SYMBOL_TO_TOKEN.put("DELTACORP",  "3851265");
        SYMBOL_TO_TOKEN.put("DEVYANI",    "1375489");
        SYMBOL_TO_TOKEN.put("DHANUKA",    "6248705");
        SYMBOL_TO_TOKEN.put("DLINKINDIA", "4569857");
        SYMBOL_TO_TOKEN.put("EASEMYTRIP", "714753");
        SYMBOL_TO_TOKEN.put("EDELWEISS",  "3870465");
        SYMBOL_TO_TOKEN.put("EIDPARRY",   "234497");
        SYMBOL_TO_TOKEN.put("ELECON",     "3492609");
        SYMBOL_TO_TOKEN.put("ELGIEQUIP",  "239873");
        SYMBOL_TO_TOKEN.put("EMCURE",     "6245889");
        SYMBOL_TO_TOKEN.put("ENGINERSIN", "1256193");
        SYMBOL_TO_TOKEN.put("EPL",        "251137");
        SYMBOL_TO_TOKEN.put("ESTER",      "6211841");
        SYMBOL_TO_TOKEN.put("ESABINDIA",  "244481");
        SYMBOL_TO_TOKEN.put("ETHOSLTD",   "2496001");
        SYMBOL_TO_TOKEN.put("FACT",       "258049");
        SYMBOL_TO_TOKEN.put("FAIRCHEMOR", "413185");
        SYMBOL_TO_TOKEN.put("FIVESTAR",   "3080193");
        SYMBOL_TO_TOKEN.put("FLAIR",      "5215233");
        SYMBOL_TO_TOKEN.put("FLUOROCHEM", "3520001");
        SYMBOL_TO_TOKEN.put("GARFIBRES",  "281601");
        SYMBOL_TO_TOKEN.put("GALAXYSURF", "336641");
        SYMBOL_TO_TOKEN.put("GICRE",      "70913");
        SYMBOL_TO_TOKEN.put("GILLETTE",   "403457");
        SYMBOL_TO_TOKEN.put("GLENMARK",   "1895937");
        SYMBOL_TO_TOKEN.put("GMMPFAUDLR", "401921");
        SYMBOL_TO_TOKEN.put("GODFRYPHLP", "302337");
        SYMBOL_TO_TOKEN.put("GODREJIND",  "2796801");
        SYMBOL_TO_TOKEN.put("GPPL",       "5051137");
        SYMBOL_TO_TOKEN.put("GREENPLY",   "1020673");
        SYMBOL_TO_TOKEN.put("GRINDWELL",  "3471361");
        SYMBOL_TO_TOKEN.put("GRSE",       "1401601");
        SYMBOL_TO_TOKEN.put("GSFC",       "319233");
        SYMBOL_TO_TOKEN.put("GTLINFRA",   "3518721");
        SYMBOL_TO_TOKEN.put("GUJGASLTD",  "2713345");
        SYMBOL_TO_TOKEN.put("HAPPSTMNDS", "12289");
        SYMBOL_TO_TOKEN.put("HEIDELBERG", "592897");
        SYMBOL_TO_TOKEN.put("HIKAL",      "2475009");
        SYMBOL_TO_TOKEN.put("HINDCOPPER", "4592385");
        SYMBOL_TO_TOKEN.put("HLEGLAS",    "585985");
        SYMBOL_TO_TOKEN.put("HOMEFIRST",  "526337");
        SYMBOL_TO_TOKEN.put("HSCL",       "3669505");
        SYMBOL_TO_TOKEN.put("HUDCO",      "5331201");
        SYMBOL_TO_TOKEN.put("IGARASHI",   "162305");
        SYMBOL_TO_TOKEN.put("IIFL",       "3023105");
        SYMBOL_TO_TOKEN.put("INDHOTEL",   "387073");
        SYMBOL_TO_TOKEN.put("INDIACEM",   "387841");
        SYMBOL_TO_TOKEN.put("INDIANB",    "3663105");
        SYMBOL_TO_TOKEN.put("INGERRAND",  "408833");
        SYMBOL_TO_TOKEN.put("INTELLECT",  "1517057");
        SYMBOL_TO_TOKEN.put("IPCALAB",    "418049");
        SYMBOL_TO_TOKEN.put("IREDA",      "5186817");
        SYMBOL_TO_TOKEN.put("JAMNAAUTO",  "5319169");
        SYMBOL_TO_TOKEN.put("JBCHEPHARM", "441857");
        SYMBOL_TO_TOKEN.put("JBMA",       "2983681");
        SYMBOL_TO_TOKEN.put("JINDALSAW",  "774145");
        SYMBOL_TO_TOKEN.put("JKIL",       "3908097");
        SYMBOL_TO_TOKEN.put("JKTYRE",     "3695361");
        SYMBOL_TO_TOKEN.put("JMFINANCIL", "3491073");
        SYMBOL_TO_TOKEN.put("JNKINDIA",   "6046977");
        SYMBOL_TO_TOKEN.put("JSWHL",      "3041281");
        SYMBOL_TO_TOKEN.put("JUSTDIAL",   "7670273");
        SYMBOL_TO_TOKEN.put("JYOTHYLAB",  "3877377");
        SYMBOL_TO_TOKEN.put("KFINTECH",   "3419905");
        SYMBOL_TO_TOKEN.put("KIRLOSENG",  "5359617");
        SYMBOL_TO_TOKEN.put("KOLTEPATIL", "3871745");
        SYMBOL_TO_TOKEN.put("KPRMILL",    "3817473");
        SYMBOL_TO_TOKEN.put("KRBL",       "2707713");
        SYMBOL_TO_TOKEN.put("KRSNAA",     "1371905");
        SYMBOL_TO_TOKEN.put("KSCL",       "3832833");
        SYMBOL_TO_TOKEN.put("KSOLVES",    "2831361");
        SYMBOL_TO_TOKEN.put("LEMONTREE",  "667137");
        SYMBOL_TO_TOKEN.put("LGHL",       "5088257");
        SYMBOL_TO_TOKEN.put("LINC",       "1779457");
        SYMBOL_TO_TOKEN.put("LTIMINDTREE","4561409"); // renamed to LTM on NSE
        SYMBOL_TO_TOKEN.put("MAITHANALL", "6281729");
        SYMBOL_TO_TOKEN.put("MANAPPURAM", "4879617");
        SYMBOL_TO_TOKEN.put("MANGALAM",   "3025153");
        SYMBOL_TO_TOKEN.put("MANINFRA",   "4665857");
        SYMBOL_TO_TOKEN.put("MAPMYINDIA", "1850113");
        SYMBOL_TO_TOKEN.put("MARKSANS",   "2708225");
        SYMBOL_TO_TOKEN.put("MASFIN",     "50945");
        SYMBOL_TO_TOKEN.put("MASTEK",     "543745");
        SYMBOL_TO_TOKEN.put("MEDANTA",    "3060737");
        SYMBOL_TO_TOKEN.put("MIRZAINT",   "1124865");
        SYMBOL_TO_TOKEN.put("MOLDTKPAC",  "1718529");
        SYMBOL_TO_TOKEN.put("NAVINFLUOR", "3756033");
        SYMBOL_TO_TOKEN.put("NBCC",       "8042241");
        SYMBOL_TO_TOKEN.put("NESCO",      "3944705");
        SYMBOL_TO_TOKEN.put("NETWORK18",  "3612417");
        SYMBOL_TO_TOKEN.put("NIACL",      "102145");
        SYMBOL_TO_TOKEN.put("NILKAMAL",   "619777");
        SYMBOL_TO_TOKEN.put("NOVARTIND",  "2305793");
        SYMBOL_TO_TOKEN.put("NUVAMA",     "4792577");
        SYMBOL_TO_TOKEN.put("NUVOCO",     "1389057");
        SYMBOL_TO_TOKEN.put("OLECTRA",    "2723073");
        SYMBOL_TO_TOKEN.put("OPTIEMUS",   "5496065");
        SYMBOL_TO_TOKEN.put("ORIENTELEC", "760833");
        SYMBOL_TO_TOKEN.put("PAISALO",    "6519809");
        SYMBOL_TO_TOKEN.put("PARADEEP",   "2493697");
        SYMBOL_TO_TOKEN.put("PENIND",     "5278977");
        SYMBOL_TO_TOKEN.put("PFIZER",     "676609");
        SYMBOL_TO_TOKEN.put("PGHH",       "648961");
        SYMBOL_TO_TOKEN.put("PNBHOUSING", "4840449");
        SYMBOL_TO_TOKEN.put("PNCINFRA",   "2402561");
        SYMBOL_TO_TOKEN.put("POLYMED",    "6583809");
        SYMBOL_TO_TOKEN.put("POWERMECH",  "2681089");
        SYMBOL_TO_TOKEN.put("PRICOLLTD",  "5025537");
        SYMBOL_TO_TOKEN.put("PRINCEPIPE", "4107521");
        SYMBOL_TO_TOKEN.put("PRSMJOHNSN", "701185");
        SYMBOL_TO_TOKEN.put("RAILTEL",    "622337");
        SYMBOL_TO_TOKEN.put("RAJRATAN",   "4854273");
        SYMBOL_TO_TOKEN.put("RAMCOIND",   "1174273");
        SYMBOL_TO_TOKEN.put("RATNAMANI",  "3443457");
        SYMBOL_TO_TOKEN.put("REDINGTON",  "3649281");
        SYMBOL_TO_TOKEN.put("RITES",      "962817");
        SYMBOL_TO_TOKEN.put("RPOWER",     "3906305");
        SYMBOL_TO_TOKEN.put("RVNL",       "2445313");
        SYMBOL_TO_TOKEN.put("SAFARI",     "3336961");
        SYMBOL_TO_TOKEN.put("SANSERA",    "1472257");
        SYMBOL_TO_TOKEN.put("SAPPHIRE",   "1719809");
        SYMBOL_TO_TOKEN.put("SARDAEN",    "4546049");
        SYMBOL_TO_TOKEN.put("SAREGAMA",   "1252353");
        SYMBOL_TO_TOKEN.put("SBICARD",    "4600577");
        SYMBOL_TO_TOKEN.put("SHYAMMETL",  "1201409");
        SYMBOL_TO_TOKEN.put("SOLARA",     "940033");
        SYMBOL_TO_TOKEN.put("SONATSOFTW", "1688577");
        SYMBOL_TO_TOKEN.put("SOUTHBANK",  "1522689");
        SYMBOL_TO_TOKEN.put("SPANDANA",   "2927361");
        SYMBOL_TO_TOKEN.put("SPARC",      "3785729");
        SYMBOL_TO_TOKEN.put("SUZLON",     "3076609");
        SYMBOL_TO_TOKEN.put("SWSOLAR",    "3197185");
        SYMBOL_TO_TOKEN.put("SYMPHONY",   "6192641");
        SYMBOL_TO_TOKEN.put("TANLA",      "3577857");
        SYMBOL_TO_TOKEN.put("THERMAX",    "889601");
        SYMBOL_TO_TOKEN.put("THYROCARE",  "4360193");
        SYMBOL_TO_TOKEN.put("TIPSMUSIC",  "2333953");
        SYMBOL_TO_TOKEN.put("TITAGARH",   "3945985");
        SYMBOL_TO_TOKEN.put("TORNTPOWER", "3529217");
        SYMBOL_TO_TOKEN.put("TRIDENT",    "2479361");
        SYMBOL_TO_TOKEN.put("TRIVENI",    "3348737");
        SYMBOL_TO_TOKEN.put("UCOBANK",    "2873089");
        SYMBOL_TO_TOKEN.put("UJJIVANSFB", "3898369");
        SYMBOL_TO_TOKEN.put("UNOMINDA",   "3623425");
        SYMBOL_TO_TOKEN.put("UBL",        "4278529");
        // Aliases for renamed symbols still referenced in NIFTY_500 list
        SYMBOL_TO_TOKEN.put("UBLHLDNG",   "4278529"); // now UBL (United Breweries)
        SYMBOL_TO_TOKEN.put("MINDA",      "3623425"); // now UNOMINDA
        SYMBOL_TO_TOKEN.put("ESAB",       "244481");  // now ESABINDIA
        SYMBOL_TO_TOKEN.put("ETHOS",      "2496001"); // now ETHOSLTD
        SYMBOL_TO_TOKEN.put("GALAXY",     "336641");  // now GALAXYSURF
        SYMBOL_TO_TOKEN.put("CESCLTD",    "160769");  // same as CESC
        SYMBOL_TO_TOKEN.put("DEEPAKNI",   "5105409"); // now DEEPAKNTR
        SYMBOL_TO_TOKEN.put("RAILVIKAS",  "2445313"); // now RVNL
        SYMBOL_TO_TOKEN.put("USHAMART",   "2263041");
        SYMBOL_TO_TOKEN.put("UTIAMC",     "134913");
        SYMBOL_TO_TOKEN.put("VAIBHAVGBL", "2909185");
        SYMBOL_TO_TOKEN.put("VGUARD",     "3932673");
        SYMBOL_TO_TOKEN.put("VINATIORGA", "4445185");
        SYMBOL_TO_TOKEN.put("VOLTAMP",    "3475713");
        SYMBOL_TO_TOKEN.put("VRLLOG",     "2226177");
        SYMBOL_TO_TOKEN.put("WELCORP",    "3026177");
        SYMBOL_TO_TOKEN.put("WONDERLA",   "768513");
        SYMBOL_TO_TOKEN.put("ZEEL",       "975873");
        SYMBOL_TO_TOKEN.put("ZENSARTECH", "275457");
        // FO_STOCKS additions — tokens verified from Zerodha instruments API 2026-06-29
        SYMBOL_TO_TOKEN.put("BALKRISIND", "85761");   // Balkrishna Industries
        SYMBOL_TO_TOKEN.put("IEX",        "56321");   // India Energy Exchange
        SYMBOL_TO_TOKEN.put("NATIONALUM", "1629185"); // National Aluminium (NALCO)
        SYMBOL_TO_TOKEN.put("NCC",        "593665");  // NCC Ltd
        SYMBOL_TO_TOKEN.put("SONACOMS",   "1199105"); // Sona BLW Precision
        SYMBOL_TO_TOKEN.put("SUNTV",      "3431425"); // Sun TV Network
        SYMBOL_TO_TOKEN.put("TATACOMM",   "952577");  // Tata Communications
        SYMBOL_TO_TOKEN.put("MCX",        "7982337"); // Multi Commodity Exchange
        SYMBOL_TO_TOKEN.put("MRF",        "582913");  // MRF Ltd
        SYMBOL_TO_TOKEN.put("KPITTECH",   "2478849"); // KPIT Technologies
        SYMBOL_TO_TOKEN.put("LAURUSLABS", "4923905"); // Laurus Labs
        SYMBOL_TO_TOKEN.put("MOTILALOFS", "3826433"); // Motilal Oswal Financial
        SYMBOL_TO_TOKEN.put("LTIM",       "4561409"); // LTIMindtree (NSE symbol now LTM)
        SYMBOL_TO_TOKEN.put("APLAPOLLO",  "6599681"); // APL Apollo Tubes
    }

    private final ZerodhaTokenManager tokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    public List<CandleData> fetchCandles(String symbol, String timeframe, java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        log.info("Fetching candles from Zerodha: symbol={}, timeframe={}, start={}, end={}",
            symbol, timeframe, startTime, endTime);

        if (!tokenManager.isAuthenticated()) {
            log.warn("Zerodha not authenticated - fetch candles requires OAuth login via browser");
            return Collections.emptyList();
        }

        try {
            String token = SYMBOL_TO_TOKEN.get(symbol.toUpperCase());
            if (token == null) {
                log.warn("No instrument token mapping for symbol: {}", symbol);
                return Collections.emptyList();
            }

            String interval = mapInterval(timeframe);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String from = startTime.format(fmt);
            String to = endTime.format(fmt);

            String url = String.format("%s/instruments/historical/%s/%s?from=%s&to=%s",
                KITE_API_BASE, token, interval, from, to);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Authorization", "token " + zerodhaApiKey + ":" + tokenManager.getCurrentAuth().getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();

            return parseKiteResponse(response, symbol, timeframe);

        } catch (Exception e) {
            log.error("Failed to fetch candles from Zerodha: {}", e.getMessage());
            if (tokenManager.refreshToken()) {
                log.info("Token refreshed, retrying candle fetch...");
                return retryFetch(symbol, timeframe, startTime, endTime);
            }
            return Collections.emptyList();
        }
    }

    private List<CandleData> retryFetch(String symbol, String timeframe, java.time.LocalDateTime startTime, java.time.LocalDateTime endTime) {
        try {
            String token = SYMBOL_TO_TOKEN.get(symbol.toUpperCase());
            if (token == null) return Collections.emptyList();

            String interval = mapInterval(timeframe);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String url = String.format("%s/instruments/historical/%s/%s?from=%s&to=%s",
                KITE_API_BASE, token, interval, startTime.format(fmt), endTime.format(fmt));

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Authorization", "token " + zerodhaApiKey + ":" + tokenManager.getCurrentAuth().getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(headers);
            String response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
            return parseKiteResponse(response, symbol, timeframe);

        } catch (Exception e) {
            log.error("Retry failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // Kite API candle format: ["2026-04-25T09:15:00+0530", open, high, low, close, volume]
    private static final java.time.format.DateTimeFormatter KITE_TS_FMT =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final java.time.format.DateTimeFormatter KITE_TS_FMT2 =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private List<CandleData> parseKiteResponse(String json, String symbol, String timeframe) {
        List<CandleData> candles = new ArrayList<>();
        try {
            String candlesKey = "\"candles\":[";
            int start = json.indexOf(candlesKey);
            if (start < 0) {
                log.warn("No candles in Kite response for {} — raw: {}", symbol, json.length() > 200 ? json.substring(0, 200) : json);
                return candles;
            }
            start += candlesKey.length();
            int end = json.lastIndexOf("]]");
            if (end < 0) return candles;
            end += 1; // include first ]

            String candlesStr = json.substring(start, end);
            if (candlesStr.isBlank() || candlesStr.equals("null")) return candles;

            int depth = 0;
            int bufStart = -1;
            for (int i = 0; i < candlesStr.length(); i++) {
                char c = candlesStr.charAt(i);
                if (c == '[') {
                    if (depth == 0) bufStart = i + 1;
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0 && bufStart >= 0) {
                        String row = candlesStr.substring(bufStart, i);
                        // Split on comma but not inside quotes
                        String[] parts = row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                        if (parts.length >= 6) {
                            try {
                                String tsRaw = parts[0].trim().replace("\"", "");
                                java.time.LocalDateTime ldt;
                                try {
                                    ldt = java.time.ZonedDateTime.parse(tsRaw, KITE_TS_FMT)
                                        .withZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDateTime();
                                } catch (Exception e1) {
                                    ldt = java.time.LocalDateTime.parse(tsRaw, KITE_TS_FMT2);
                                }
                                CandleData candle = new CandleData();
                                candle.setSymbol(symbol);
                                candle.setTimeframe(timeframe);
                                candle.setTimestamp(ldt);
                                candle.setOpen(new BigDecimal(parts[1].trim()));
                                candle.setHigh(new BigDecimal(parts[2].trim()));
                                candle.setLow(new BigDecimal(parts[3].trim()));
                                candle.setClose(new BigDecimal(parts[4].trim()));
                                candle.setVolume((long) Double.parseDouble(parts[5].trim()));
                                candles.add(candle);
                            } catch (Exception e) {
                                log.warn("Failed to parse candle row [{}]: {}", row, e.getMessage());
                            }
                        }
                        bufStart = -1;
                    }
                }
            }
            log.info("Parsed {} candles from Kite API for {}", candles.size(), symbol);
        } catch (Exception e) {
            log.error("Failed to parse Kite response for {}: {}", symbol, e.getMessage());
        }
        return candles;
    }

    private String mapInterval(String timeframe) {
        return switch (timeframe) {
            case "1min" -> "minute";
            case "5min" -> "5minute";
            case "15min" -> "15minute";
            case "hourly" -> "60minute";
            case "daily" -> "day";
            case "weekly" -> "week";
            case "monthly" -> "month";
            default -> "day";
        };
    }

    public boolean authenticate(String requestToken) {
        try {
            log.info("Authenticating with Zerodha using request token...");
            String checksum = sha256Hex(zerodhaApiKey + requestToken + zerodhaApiSecret);
            String url = String.format("%s/session/token", KITE_API_BASE);
            String body = String.format("api_key=%s&request_token=%s&checksum=%s",
                zerodhaApiKey, requestToken, checksum);

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Kite-Version", "3");
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            String response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class).getBody();

            if (response != null && response.contains("\"access_token\"")) {
                String accessToken = extractValue(response, "access_token");
                String refreshToken = extractValue(response, "refresh_token");
                int expiresIn = 86400;
                String expiresStr = extractValue(response, "login_time");
                if (expiresStr != null) {
                    try {
                        Instant loginTime = Instant.parse(expiresStr);
                        expiresIn = (int) Duration.between(loginTime, loginTime.plus(24, ChronoUnit.HOURS)).getSeconds();
                    } catch (Exception ignored) {}
                }
                tokenManager.setAuth(accessToken, refreshToken, expiresIn);
                log.info("Zerodha authentication successful");
                return true;
            }
            log.warn("Zerodha authentication failed - unexpected response");
            return false;

        } catch (Exception e) {
            log.error("Zerodha authentication failed: {}", e.getMessage());
            return false;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private String extractValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }
}
