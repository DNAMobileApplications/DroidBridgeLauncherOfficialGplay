/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 */

package ca.dnamobile.droidbridgelauncher.modcompat;

import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.dnamobile.droidbridgelauncher.feature.log.Logging;

/**
 * Repairs the isolated BTA LWJGL GLFW shim used by DroidBridge for BTA 8+.
 *
 * v1 preserves NIO output-buffer positions. v2 restores GLFW buffer overloads
 * required by BTA 8.0.1. v3 adds a BTA-only adaptive VSync fallback that leaves
 * the real EGL swap interval enabled and waits only when EGL returns before the
 * next display-rate deadline. This keeps live glfwSwapInterval toggles working
 * without stacking the generic DroidBridge pacer on top of EGL.
 *
 * The isolated component is generated launcher data, not a user game jar. Only
 * exact known DroidBridge GLFW builds are upgraded.
 */
public final class BtaGlfwBufferCompat {
    private static final String TAG = "BtaGlfwBufferCompat";
    private static final String GLFW_ENTRY = "org/lwjgl/glfw/GLFW.class";
    private static final String VSYNC_HELPER_ENTRY = "org/lwjgl/glfw/DroidBridgeBtaVsync.class";

    private static final String ORIGINAL_SHA256 =
            "8e6311329fa0318206d0ad471d949bd6684646ab64005c003aae2db24140601c";
    private static final String BUFFER_PATCH_SHA256 =
            "6bc2bd25b71b609484302a70f68ba57a1387558bd6eb40a66fd558c271e1ccb0";
    private static final String API_PATCH_SHA256 =
            "523b266dc1bc7b5b52d8992ca42dbc96f1d9687372ae44f321a221a6468534e6";
    private static final String PATCHED_SHA256 =
            "f380a339de3652398a38591acf8bb61a9fb8448c9d36b51d099a5b07ccad690b";
    private static final String VSYNC_HELPER_SHA256 =
            "03b4d8fde065b1c67fb4c929ccfd856cce4191d17230b5e398ec84b5a63366b8";
    private static final String MARKER_ENTRY =
            "META-INF/droidbridge/bta_glfw_bta8_compat_v3";

    private static final String PATCHED_CLASS_GZIP_BASE64 =
            "H4sIAAAAAAAC/7V9B2AcxfX3zGw56bSyJcuyLVm25YYlWXLHTRSfpJN1shp3Ki4k4iydbWFJJ06SC4RQAoQAgdADhF5MwDQZhAiEkhAIEAhJSCNAEiCkU0MK"
            + "JXzvzc7uze7tSU7y/xyi3fem/d6bN2/ezO7OPfvvbz1KCFmlv+8jjJK8eGLn0t69J+7sXbqzd8fepRsbajt8RKUkP5kQH4j1w2Vjw4plfqITn49kGCST+CmZ"
            + "emJ0T3Rpf098aW1vPDpUNbxjRyxBSUa0tzfeFR2KUVJQEipt8MpV6ScGyfaRSQaZTHIomWxnsmpRd/T2DADCEqkCu+wUkucjUxFGPmRJpu8filnlZziaTqZA"
            + "6elkho8UGKSQzKQkh+fpjfbvXNq8/cRY1xAl+lE9/T1Dx1CilJS2+8gsShYlM7X1Dw4PDMQTQ7Hu5oFYIjrUE+8P7uuKDeCNnxSRGZmEkmKDzCXzQArUKJAN"
            + "SX0O7h8civUtjeyKJmLdDT3bE9HE/koss8AgC8kRgKgPC3X09HfH93b0dA/tooSGMEOJQUoxQ66UoS7Ws3PXEKYuNkg5qaBkEk9t7+mOxRvj3dAJRQ0evQzp"
            + "mAraWEqW+chyg6wgKykpTJ+VEl+0uzsRGxzkiqnPhJJHGmQ1ItI6QjWtdX6ylqzzkfUGqSRHUTIzRebGWF88sb9tqKeXksy+WF/L8FCoHxSuldSHStuxwmMM"
            + "cixWqNcFQxvrWlGugEGqSLUllyl1YxRMY3pDtL87Ee/pXjoMNS4NJBLR/ZAAIgVJrY9sNEgdCUG5ZN81xPt3ghh7or3DseYdaFz1wkbsZCi9iTT4SKNBmkgz"
            + "DATPNkADO2MA/IiSBrf5VJamsnzkOErme6jWlKYlAWMsMdQTG/SRCCXzkuVDvb2xndHeQGLncF+sf8i2Mx9pA/mT+SJDiZ7+nVXDPb3dsYSftJIZGWQzDL+m"
            + "ePFe3kTxQLynfyiWKN4RH+7vXl+MebYaZBs5HnQdHYAh3k1JhSyNWaVDGkcrlVjF57GKTjAxpyJTM0YNsp10gW8YipuJ4D1KUotA3jCJ4ciE3sn3gtOOJrHL"
            + "ID3kRGsgVMNIqgafsz3atZuSuV7WLmepRDPtNUgf6UejivUFTKuORHeAjc8qSR2qLabuKkvr/dD4gEFOIuBgpvZj9ZHYkLN9sOV6zDhEhn1kj0H2kn2UzJkA"
            + "EyX+rkQMXKYJYgGqcyIxUBEnG+QU8gVQla0IGKmDyVoXpqtFzlaJUn3RIKehVDNkqeRcfnIGOdNHvoQSneVtzantl4wjiAMCCnOOQb5MzgUIpjDDicF4IoiK"
            + "l+rzrCw1JxfpPIOcjyLNtEVKzegnXyUX+shFKNXXYDwfXvXgbNMJ5oEFZbvEIJeSyyiZJsnWEpc0dUT62qR8XK4rDHIlylXglEvK5idXkat95BqU6htgTodT"
            + "NSWl48oko0CJrjPI9eQGawzWgAubYAzKWUCOm8jNPnKLQW4lt3mPD2eVujk+KAmWTFR5qLJ0wvZRgtsN8k1yByVTuATBRCIudfA8ryoceUCGg+QuH7kbZbgH"
            + "5viJClCysWTCWtOAd7aM6O8zyAg5BK6Xo69NRPti23mAE+k5OZZsstyrtjS5uXU9YJBRtK45lnWlyewnY+QhH/kW2tjDlJQdfjOULE1jaelwobzfNsij5DHL"
            + "1dXHwTX3dO2ewNW5s4GET5Dv+Mh3sc+e9PZiqVU3lhxO3Wl6LgUCCvOUQZ4m37dCvU2x/cnGir0qkXLwTnrWIM9hJ+VZnSRl8JPnyQs+8kPsmBcpmT1+daCD"
            + "NJ0ht4mYf2yQn5CXYN7jmBvjECLLtr3Aqw5XLsD+M/JzH/kFqv+XMMgmLkLJppLDqDmN8t3toxy/Msgr5FVrmmmMDw/CymBoKN4/wTTjkZP3xa8N8hvHNOOR"
            + "0U9eJ2/4yJvYJ7/znmY8gaSbZrywoGy/N8gfyB/BLrhska5EvLdX6mmvqpyZuER/NshfUKJplkTOPH7yNnnHR95FYd4DZz9hpRgne8vhahxF+MAgfyMfWt1j"
            + "BsjVvfHB2ATd45EThPkH+aeP/AvN7SNvtXs2EDncFtKYnRcWlO0Tg3xK/g2+VZYtDsFCP6g42it7yHGq9ShQCatxSn2UgaRUgUj+PypNSbTkP2tvfLm9AIL4"
            + "VDOoDiAdXVsb7xoePKyudeQEgTOp30ezUGBjvK51NRA53BbGFdGJBWWbZNDJNAcW0JJsoS5wPzskV1uWvkZXXpBvCs3z0akoXz7o5XALUtJRctitjCujGxFK"
            + "Od2gM2gB+DrHWnxfT59jel+cvlJ3ZpBzJi3y0Vko52yIQQ+7JCVbSg6/nXElTQGFohYbdC6dZ0XsYqE+YcSekg8EXEAX+ugRKOAi71jco/LmksOqfVyxXBE7"
            + "LTVoGV3sNNFwbAesfXcdlom68oJkFXSJjy5FyZaNZ6IpjYxnoq7M40roRoRSrjDoSrqKkumSlM7oc1H6Ct3RMF1t0DU4FRZaU2FqPj9dR9f7aCVMh/QoiEMP"
            + "q3JQdJop0QMIinWMQY+lGyjJ6hnEXOFYtHs/+PytflpFq320xqBB3L+TdjMjfPOCkoz+aH+8tacvhtVsNGgdDVmBZwiio55oLyZCXTWZdBNt8NFGgzbRZkpm"
            + "eaBbUDvc34U7UIOUqFgcytX76XE07KMRg7bSNggQUzZQ6pugSV9P/5747hjcqSA6bmjSDoNuRqHMNWRoMNQ/MDzERfPTrXSbjx5v0M/Rz6dCsVRTlejp3hnD"
            + "ODg61LMnBv2TrALb2Vq6FYU6waBRlCizNZbo64G8MYTcZdBuGrOBtZvA2hWiExVL7TJoD5bK3ciX2AmY0/jUtm8IS+82aC/ts0u3mKXrM2mcDvjoSQZNoGTT"
            + "U3RRvSvWtRu0p1fXBas3RfyQf8igw3QPwOvCNHMLyNr8kra9Q6XtaJL7DLqfngyw+KZUf7R3o2WV6fdb3PuMYFIt9AsGPZVv3u7FTWY/PY2e7qNnGPRM+iVY"
            + "FNuth/rtnX0FdAsyOXbW7WRe59kGPYfv3+7i+9J+ei79io+eZ9Dz6QUObYg9NavmSdYzg5qeBN+DL8JG0mTnI/NCg16EFp+PQoIKWhI9fdHEfhHxY8MXoyiX"
            + "iM3JdFWhGV5m0Muxo6fIs5pZD8p0pUG/Tq+iJNvcSg0MDSV6tkMPTjaVwDeG+b4zvYZ+w0evNUgdvQ76R9rGhQZ3opjTbNXJ/MosegO90UdvMujN9BZoyFEv"
            + "JcbO2FBzoia2IzrcC6pZ67HpfFjb0AjwNoMeoLeDVwAltOM+ON/MDyGCO1Bft2TQg2ASK5esWrLMT++m9/jovQa9j46AHtPs50eGuEPLGMTrRtwWLy4pbRgv"
            + "cyXWfL9BH0AkfpBO9An264MGHaMPgYcS/So9yJiTxrqlZxn0YXTDj4CTx0cK42ReYJkSIHnUoI/RxwHJoI0ERzN/JEHpdwz6Xfqk9fxhY7SvLxqO9kG3zPaq"
            + "3k6vRBfylEGf5pbVGN0dE85DuBL0Ic8Y9Fn6HO8M7kPaxfZxO5Z93qAvYNmsyN7ogIl2EPkvGvRHyDeQjxaU2BPtxdp+gv7sJawiBFXogeefjxH4hzPELwz6"
            + "SxwsuV1m4+jxG3t6e3sGNWL+OwqrftWgr2HV2dV8s03ydr8x6G9xHrSQtmBXvWHQN+nv0H2j+GYZyxPNKQmFJAvEDd9I7KThWH9XrBI3yDPo7/HpWLQvhjX9"
            + "0aB/Qmc6pT9Zk9U6acFHai30rwZ9m74D4g31DPWCNcxIV71CGFGwxPsG/QAHrr8HJwXs9UGFKMso/NmAf5Yx/KPgHx8WysI/agb9BOawmnBzqKYqHKrZGOwM"
            + "B5tqguFgGDX5b4N+xmApp4PVxvr3uB4BpX1oUpnBGHTlnuHe3dH+zpN7+nf7mco0H9MN5mMZzolbPCDRQaBo76DrQYg1vLdmMFj0Ze8Ex9kz3Ne5pyexszeD"
            + "wVIo03xgGxtcCeIUM/yj+MkmegcYM8s12BRuVH3Rnn6hYeRPNVg+zldFkgts7xns2d4ba+7n/cGfblI23WAzcNKcxOOhXfiEEvNCA6zQYDPxkZmegJG+J5ZJ"
            + "q9gsg81mc8DFxHHiyDfFgEkDnDUsEIeg3j7QzFwYrtjs+uKOaKIfZF9fvCPa0xvrLh6KF5uVWY+x/Gw+W+BjCw12BD4iyvOoEKbkAaR6AW+YlRisFB/zTh4w"
            + "c4D3aU1Eu2IIeLHBytEDqYMQbSFjicGWMghltd2x/QEE7HTXlhslQbbCYCtxQGX2xvt3cj+KyjnSYKtROfn2xgkoCOQxdZTF1pKoj62DacDbcsFmWaXBjsIJ"
            + "lO5D6hiD8cehdH8GC0AUamoJKi5GyMU74glLMRmsGkZneTGfy4/2k1YWNMg2Vitm0nTP5jJYHQhRXmxO10eDE2D1BtvEnYAd7db1cHfFIDDsZk3QwTC20UkB"
            + "wBaDHcfD0sFd8eHebr7lgfYUMVirFNnh+GsZ7huAlrGJdoN1mC5sKJqwErCFLQbbigrUusCs27FLjjfY5xhEgDp0SQTnllzrsSGfH4FVmcVOYKDX7QbrYt2O"
            + "2ZMXyOgZwsf0cT79ymVDgg8V7GA7fWyXwXrYiVbo48gCNrUrOtgEg4VPl1uxRK/B+lg/WE8/ZzsfZwpTQWEHDHYSCutHOYN7wPsOoqiDBhtC36+jqPXo8dke"
            + "g+01Pf5QfMDSCihzv8FOZqdQUmBFfGA0/TtjIkTBpQJ3+mZ8yE412BfZaaColBUL9lw9ZvOxM6yxw9Em319gp7MSiAVIAwTg7GyDncO+DDXZ0fXGRHT7du6c"
            + "MLbG6ZF9xWDn8ejI3spGP4spFxjsq+xCSIGuq4nv7bdivfw0r2VMYV8z2MU8OOmKDkS7eob2I/NSgzSxy8wJuQrrvcJgV2K9k/twFzRZMwp/lcGuZtcgZBE+"
            + "2A+vKFlRUp9suSY+DL5NtJ2GXdpuzY4qArneYDcgukxwSeA9eedMYTchvJvxVZdtVWleOAFV3mqw29gBwGyqsrq3Z2B7PJro5tG0XdD5JHwt+6bB7mB3oreO"
            + "9bW11q41lwZLS9LNfOleeKHsLoPdze6BEWA9MrQAWJNNgawcqXBpewa7DybbQKi6OIbGW7x9eLC4C2YO3LmNJbBDDhnsfvYADOcTxbOOmuhQFNryfvMHSzxo"
            + "sDHsQv92voeN+TPYt6CPoz1xCDvj7BFYErFvo8Fu43YNrMeQ9Thn1XJT1xmEZmey74JfLAnVpnvPCEp+D0s+xUvWiEHyfYM9w561Ag7ZTsDNZpTUb6vZVoM5"
            + "FfYDgz3PXoA+2IkLLUtd+V5OtRLn9BcN9iP2Y7Rh0BGYCRjezPQdBuv3c9n1+OenBvsZt/2B+GAPH4vA/AUa1y9N269Hxf3KYK+gi+D92M6jiYZ4FDx5XbS/"
            + "uxeWtXez1wz2a1xsZffxNZUIaDPYb2HSbt/Uuaku3Dk4nNjB58C17A2DvYkm5hMmhq2+hWqF8GxZibdJpF1MZbA/AjTRiHhxxm4MKv6zQSazv1Ay12t9UD08"
            + "OBTvk2z2bYO9g6+ZTcE3wQZi3VK3YvJ7BnsfX//KFclJhDgs/wZLTpKfwf4Onhl1NTg0vH3J3uQ7VRDLsX8a7F8Yy2XhCsRcG8M8+7FYU0olxEtWUORTg/2b"
            + "BzSxRCJDgbJddshi1VDs1VwxzNSelRb3x2Eyjw2VF3eb6zuoC8Oe5SvWLisGFRavWQED4hqFGYqiqGge0cRgjL815fl+TChD0cG3c9Vv57sjnYnh/iGI90Fi"
            + "JcOAwBjipixQpfXumU/JgrWM4wU38FGDO3rw7bT+3fzJs1/xsxI/OahMMpTJSg5UYD6O52EXvq9SejiPyzdhIL9UyTOUqXyPIBLaGmyuhVWbMs0ghcp0fGkv"
            + "jRPCcgWGUojlfOFgTVWoNYK8IkOZhbzMjeFgsMnizjGUYuRmVDW0BS3mPEOZj8yscLA2HIzUhQOtQb+yUDnCpywylBKl1HoL0RymvVF80S0TTKO2J9bbPYiB"
            + "VOk2SeOJ2I5emOOX8uRKv7JYKfcpFYayBN9zmp4mH6CHGptg2ZMBCxGADWrq3BTc0gmuQ1lhKCuVVbgOxbBosKMHX/3z7OWt0BzM0VBmjaGsVSCkzBwc3j4o"
            + "nNM0jAU8vZNSaShHIb6soXgbDBroncEY8o+BatgLGbAuIrQzQ6mCSzHyawwlqEAQ6UvEBnphFFNSk9aZTTAruaHUGUpIQGmI7zWh+JRNlBSnvv/W1RUbHLSj"
            + "lAylEXwZGNwSbnBL0OAyFPCKBb092z0sf8lg3K8cp4R9SsRQWpU2sLMU7yMGAygfh0YTn6Up2ewQFk2iMlWYVM7W0one9wSvxru+PRiOhJqbOhsD9c1hXHjH"
            + "+6HzxdaMApGHkpIz1NQcxhR8S9eREg62h/AGE4llW63htiAyKEjGGbWBhkgQ5mpOhIMNwQCSZloLjIsI9IhIawkGWrEswz1QZNUFWjurg02tsBiusbIhr60F"
            + "+sOmwvjmJshiM2qaO5pkuiFYy+vNkOvlpXhNeS4eFsfs4NdyHHVAbuRnWiqy+VYJwyqBI6ytaVMT8j+DfxZc5EdaAtVcRcVW08gNtDRHWsPNLXU8aZFcoLq5"
            + "sTGA3HKZC/3SFkFuBfgRm9sSDIeaa5C9xNFmQyBSh9ylluqRuww5y2TOcuQslzkrkLNC5qxEzkqZswo5q2TOkcg5UuasRs5qmbMGOWtkzlrkrJU565CzztI3"
            + "lyTYGKpubjCtrlKWMXhcW6ABuUfLNXDFBWROFXKqZE41cqplDtdgjczh3RKUObXIqZU5G5GzUeZwndfJnBByQjKnHjn1MmcTcjbJHC5Xg8xpRE6jzOEaaZI5"
            + "zchpljncfltkznHIOU7m8MEeljnczCIyhw+nVpnThpw2mdOOnHaZ04GcDpmzGTmbZc4W5GyROVuRs9VyPlwbOOaqwoHqTUEOZJtsIFXAts39eJickoLx0S2V"
            + "+5xc6cZwoD3YGahGf4OJJ8hjuaM53FBjjo4bU/l8jNwkD8NgpDrQgiYD85psoujKkEsth8iVGUCTpCxFCuEo8AWLZNWhpkgwjAipKrNrwLO28tya3CKXGbm6"
            + "5RAtBSLTJzOFE6MZlqfl3YoWQzNlmVsCG4OC75chc75VSZZcc11zI0dmyFIHm3CQ0Wy5ClBapLOhuRoHAJ0BkXTSBKvDzQ0NdlqB5cm54bc12gmFcpe2hENN"
            + "rVgUQjVMnClrpiXQFuGoimR5a7GP8TG8xMLupfMdLPSBdIGDhU6QLnSw0AvSIxwsdIP4WF5ioR+kJQ4WOkJa6mChJ6RlsgJrl6P/xgfsMo/jL3fyuAAVTh6X"
            + "YImTx0VY6uRxGZY5eVyI5U4el2KFk8fFWOnkcTlWOXgruBxHOnlcjtVOHpdjjZPH5Vjr5HE51jl5XI71slVuauETIO4aOpi85YCLyZuucjF529UuJm+8xsXk"
            + "rQddTK7GWheT63Gji8kVWedick2G5BgCmDXB6lAjnwZpvTyyMCnUHqrhFr9JHlmQ0tjW0BpqaUDXSxtcaZG2qlbwmNxdNMoOB9ICNXwIN8mDEdi2l2t2J4gZ"
            + "mrbIqLk3j9SFTJfUmuLoq5ubIDDi5drkCnlioIGXak+tsK3FBNEhC2ROAXZjm1OnB6m1LbICzVTR3FaPOq32tsnd1BhswomRHu/wvoFIq+WJGptrTDiWh0WG"
            + "wGAZMLKgYUcRbM2Cx4tInhO/b8u1UyzvaGmosRn8XmdVW2srhPDLPbkrPLkrPbmrPLlHenJ5DKh5Jq3x5PKA0Gd1kiPJVKJXQhCVOT01QSwTZqSmNIZqahpg"
            + "dObymbettjYY7myurY2YUcIcsHopATcRsEuPsQZDffOWSGuoehMq08VZkcJZmcJZlcI5MoWzOoWzJoWzNoWzzrKpJMJlqSwe1PhT+TyoyUrlr0xl8fg/O5XP"
            + "VwGTUvncDiZb5mvzzS41R//GQGOwJVBj9VAgDb8qDX9zGj4M6TlefDOibGvko6rYK4cIHkWWGZ5gAjjKCrySIq2BcGuatI1t4JYpmZ0WV2tdW2NVGuAmLJFj"
            + "pleOGrzHZe7stKliZMxKm8FcUKdPN8ecp1LMTvUUHEI6XP0XeqaFwtU4JD0TIzCPhCGxyCuxNRwKNG3Esk48gc2hiKnPzemTtrih8iRTyZvHSdviVm+ySsCz"
            + "cSPazJx0he0c0zyq4Ooz546m5s5gOIzbNlMF3QqrgVBrKNAAPqkGd4ep5fkgb3VbOAxTMZ9NgptbMdneWAk1tUOhGpiq2xoxwd71sRLgL+7jUNz1MVOa21rB"
            + "KcKM1tgc3oIpmoUj0BLqbGsKtAdCDYGqBl5Kt1Rs7RW50n2Wu29pCLTWNocbTckwKcMSAdm41+MsmWmpCSTsCDWBZcoC+q3CIHykOewunGXBqg0GWtvCQXe6"
            + "YRlcMj3U2NIQbMSQhis425qUa5urYQapwTc4qDUzhwBJqDZkcpnFDQdhysAWrKK4a8ZJMx1CtmbcG+alNKuHAm2tzaLCLZhgr99qG5oDraGmjcj02TEBGEuj"
            + "aQQMNWh2mbl1JnSBKZnWmIGQrgmWl9w+asNgcObkhnn8Fsy65nZz321KUuBOHH11zR2Y0bDWJ2b9lpvnvnwLBjoRa8AIljznRqxqMTQxQ6Xmmgi+GWNvM4YD"
            + "1jTd2NzKtxmVZZolQFtTAwgMSnPWinlAUxlmdzQGbU2Y5tCEJtXgYtbB7I/rQ2UDsyxapNSEIthvNZimuNIg4EIL4Wmq3WlgxB22vpUTqD3eqoKBRimBWUJy"
            + "H1gXCIWlRMXSW0szrF+hqzvrAk01UoakitC0gp1BuU3NldgUkRJ1a3xYiR2gu2Syz50cjMhVZ1hjTyQHYEWeTM1M+h4MlBuaO4IyaL+90SpKW2ZjcttdXGvn"
            + "1RbcMnUYEk3Baj5cVGIrGLrKkcAsHdrRBW7W2laiYclCUV91c6Czuq4G+gAwNLeFq4OYI4fadsJzYChfFQhjiu1GA01bbA+Gr/GSFLcGLmrlCkyiKUm8Wkyy"
            + "jSFZKrClIYD7Izp65lxn4ublyy1kNq+praEBc9t7PzXN6Pn5LJktugxmyFArSMZQMjNU5A+xbLbtsfApls21N58CDS11AZttbz7VBFta62y27b8ircGm6lCD"
            + "naBb8geqq2FFIsPxWfKbSU5QGfYswxMd0DKdBZ0A7ZA20LZZxO6cn2W5rQg+VmhGlr0vFQmgt+fZsm1BwhurcLSbcwTLmWQliMd6nei7MWGybYvNbZDX9qg5"
            + "OZaqqhtC6HBhvsSX5CQL5POX+9lMmkR8HGN7D5GYfBLDilV7CrQSm6vaIq0wlFGuYs2quLklCGESzrIdgTCuORtb8OkLK9atoSNy1IAoG5OTLCu2J2+RoSXc"
            + "XBviyinOsKLEJDL+wKezKlgXaA/xWag40w3fCmww0W+1biVWh2EuRsmF2rKs0pHqQEOwE2bJRpgkW83SRrJ9HLPhILjPgGuGW0aT7WMmntrZFMA9SraMOWvY"
            + "GA601IWqI52RjlBrdZ057y5TLA3AWIReDUQiVvkN1PKRmBRqgsC/qdquHWcYMwDhfgHnvapm1L65T8BaqGWcoBKQ1zIcoWiuAYXYjkmwgxErxd5ChuLJbrd6"
            + "HJlBWNSig4YwpTogZtUc2xIbmnE6EIrnT/j4GlhB1zDdgQQdn9ntlkQiBWKZYNIilKSV2+loZ3IO21VjpW57sRY1bj6EQW24xa+02A2kZGlqbsIGWmzwTSBx"
            + "e1JArmDTuQQ5MoktpI00BiMBZ0pmpGdnf3RoOBGjZLXzjfyjPF61T31Ye0wlJU3eR8kc5T4c5rC+rcAKc0GhLVZAzA/HAVNIbRuYckbz0BsI8qv5++6TG3r6"
            + "Y03DfdtjidbodnzneUpDvCva2x5N9CAtmPp268ikoV09+LqwF8pK80V7+zH3vJLDeEadzd+XBUVYLe0Vb3crcJPmk/sUdcDY9DwopbakfqIzTtJ8/OU4BgVf"
            + "Ft2+Y7g/zZENzupgYumNDg5JxzyEzVcExLvOLVE8gWAolgj098eHouJDp1mpmjJfEGjdPxBDAHvMDyn4pCXexy2DkcL7e9/SqF3X0qbh3l5UZaXwK12AjmOf"
            + "nubUFTDN9GqSM46jKsdBK+lOGkmpTvg6RNgHCRxlYfqDVChpTQM0NW86rB5np1BSetiVCmfbxdNimMZBz0h3SgolLeNBnvgzy9SDUdJ8XOhRoYiGTLADcVO/"
            + "1kBxHn2SZqD8p0eepBkgzmqEXYIvHOCIpgpEroNM5h/GOSZiPMSQy+uaPf6BIpQc7y1omvxpZE53hgglFf9R5SJu2JFMx1fuHYM19aSQIw7voBAxtqzXZXml"
            + "U1JP8sCXreonOJojjRbkwzvSnMfjqEQ84N8d28/BTLOPsnAdxrHwsM7iEAuXPpPvcB2ex1ykcR0eedOe85F6HkYa1+FZqXAd/IVy85VkDjrf8/wLSuq98Tqz"
            + "pYHqOvIizcEp7qrEsnGQsx0K9TzAIo1C/7cTLNIo1LNSoVBzMuzCNA56rhO051kU28fF/n9yGAUly/7TJkRQLAQyswxiFo/OcB05MW5n/LdnTozbGa5KHZ2x"
            + "A9M46CIH6JRzJDaPB/t/OUhi3AMiUioWSwITfI+ZyuHPcsBPPR5i63j4/7fzIdIcq5WuarG+NUXoE8mO2MTjBIiW8eD/N0dAjHvwgVdsYuK1YhOntaQc6TCu"
            + "tfwvZzqMay0pFTusJWGmcvgFac9voCQ8HvbI4VqJK9ooOdwqxTaVCdmOMTKwjHnAQm5n5w5x6kLAOoA1G5Pt0wzwE3GRvY6/kK/u4pfJ1rfxvdGhHfFEn5jR"
            + "LDJiHWRrfmCyFb8ucOZMOfkAFOlcMTkWStNEGVdQBcNF/rooeWKAF6+0HfrQKwHWWSAULqwssUTcwb+wSf9RCn4Pan16VjBOLjlkKS4rEx/QJZvh35D5RAbQ"
            + "2L4BZKj7+SXbUTipCVG2I57YDUvrqDkZHKYm/iONifYchxckw3cX219SXx/Cf1g0SwyTMDekPEdF5lkH4oDeEB4VKxh5joqtbJOsb/YxWI6DFR9b8r8KpvVF"
            + "T0TIGlg6XpVEbE9SXNGQ9VWWIdh8JYIfbKTtbhQmqzsG8VQP/6Qg2dv2eQOD6Y8tc50hAOOmsxNUyE9n0AbN4xBmj38CAoDlGQUsnz7J8UVm665EfK+56cJ3"
            + "Nvf0dMMqPIYf3g3yMWAdjyCdRzA3DdrkkQSiWMRRbJ6390uWwm5QEzxvvtk+pu/EdOTa5p56xoEYqtIBBhYC6egC8ywKcZslxDKPpFFKSmsEL2Lx1JIaDmiI"
            + "U7lS/oQ4zyJf5tUmzM9Q9mPP4PaWQOA8rSC3P5WXgV8541EFeO7JEH4CvLO30TRGvDPNMQPE7I4l0LdwKDWxwaFEfL9VB37LvKBh4vOcK8X6zx54reYZB/le"
            + "5ypXCn2LA0GS32gPJvUhf/Q7PWXqa4AIBHNnlNRbXiADxtde87DxTLg1vwdHdnSfzY7us9g57rhFjP06GDyW7LzreSRqcXiWJFwxjJOM5LeooTSfgcGoMS0Q"
            + "t6XQ8IpK0p4ygbnzkw1Ekh+om0f1bHWHX44c4PG2cv/TZdJTHFlF9xgchj0miyzf7g0mNyXehvW09+AL9UV32u4Fy+o9yBlMs5R3ZBfxBC+QxMZ13wKLR/ML"
            + "dOhC99629H25FKbwL8277Y+2nZ+LQxmKVr7L6ttoz5BVf76TgYORHwLhG7LuppiQBoeCfQND+3m2pF8LWUd2QGf18Ytlc1KKNd9siu3HT+pgoO6O7ceTaLqi"
            + "/V08x5RkjojNxGAHP/lIpvHYoju2L1lA2ibgO+zmTU7qV+XT03w8DjC6OW1764ijnF5SX8M9meSOzGQ8PCVtL1fCcK1HuEjAwjN9RgxRdsUxGNzPL7lJ323a"
            + "RpkYAGbbMC31d0cT3RYG9JYDMadbs9L0LnFjJPcwsbpsh5gQliEdju7lymyMo8lIMadufa+oRBOQmW2rSsZNKd+n8xV2GIfTYDLoAIvhczePRqxtthaYibkp"
            + "KSf2dCcDGivZNBQ3N7AvJsWvFtfs/8FkdmvHri5qm7iVNzSIh9kMRD2axDcKhQQSt20wlrBPGyp0bSk6En0D1h1fxbQNdEN/idYaowN4TMRg+m9ntyaNWpRx"
            + "qkAwof8x/lsIDtU7GrAz8SoxzMH8cyfMDTNHybYQ/g+tnW0LmR/YC9owby2y0Bk3y3sxZrHabbXcH+4bNHlsWy1Q+wXl24HfiKMpLpPjbPlAAG+uPRA3Oia1"
            + "aQ4WX9aYc6raG9uBJjYUh6hIS5izor49DvbSJ7ppo9c2FwKuEfUG9wF70DkmFPOIET47unTRsmv/YA/UYQLw8Vm5sRG0Yk7KeDvFDMPMDQY7tjFHziBUYfOm"
            + "WstF+QiB8QNmVHqvyDdXIMPx2JOIdYf4R6tdMVsiUB0bgAVBtjxBlgmbM9uUhM44qqtX/OJKccP4H6KjNeFTTvExdUHa77EhbNwr/4KKIX90D6RdSQB9T2H6"
            + "D7t9+v0wVUTiw4muWG0P9iD/sHYJ5od6Qv39sQT/MhgdSKZ9PKJPf9j7aGTn2gGUat749Ee9j8GX53af/rjj22VnptTDRWvwkbZ5RmLVUFSMSp/+BH7p7vSW"
            + "hv5d7XW//h39SekYD/QThv4UewETnpYS0Fsa+jPEjwnPSrUJh2noPyD5mPa8VAh9pqH/0Ex4Ec/Hc3tOQ/+xCeInUrGNwz2Q8FMTxM9wNZB0Yob+C5P/S1z7"
            + "SU7H0H+ln4sJrzjOJRXrMEktfEhXx4fNNdhreNxrNLHbGhU7cbzxLIb+GzLTr/9a/62fGOyn/OeFvE9BNOiZ+pt+epr+O0O/Ur/QT6j+e59+Qeph587OaR/c"
            + "39/l0/8ENhTdAWBwhWTof8FG/6z/FT9375cXTYb+Dn0ck94lcwkEIYTQLuLHM5fw6wE8y4tfX6HN+J01Hs/Cr2PsQrgWKO3kCEKVDvy2W9kM9BaJ3gr0Nok+"
            + "HujPSfTnge50pZ/goqOu/Nslugvoblf+mCv/Dlf+nS58uyS6B+gTXfLsluheoPskuh/ouEQPAH2SRCeAHpToIaCHJXoP0Hsleh/Q+yX6ZKBPkegvAH2qRH8R"
            + "6NMk+nSgz5DoM4H+kkSfBfTZEn0O0F+W6HOB/opEnwf0+RJ9AdBflegLgb5Ior8G9MUSfQnQl0r0ZUBfLtFXAH2lRH8d6Ksk+mqgr5HobwB9rURfB/T1En0D"
            + "0DdK9E1A3yzRtwB9q0TfBvQBib4d6G9K9B1A3ynRB4G+S6LvBvoeib4X6PskegToQxJ9P9APSPQo0A9K9BjQD0n0t4B+WKIfAfrbEv0o0I9J9ONAPyHR3wH6"
            + "uxL9JNDfk+ingH5aor8P9DMS/SzQz0n0D4B+XqJfAPqHEv0i0D+S6B8D/ROJfgnon0r0z4D+uUT/AuhfSvTLQP9Kol8B+lWJfg3oX0v0b4D+rUS/DvQbEv0m"
            + "0L+T6LeA/r1E/wHoP0r0n4D+s0T/Bei/SvTbQL8j0e8C/Z5Evw/0BxL9N6A/lOi/A/0Pif4n0P+S6I+A/liiPwH6U4n+N9CfJWkV/LFKJZoBrUi0CrQm0TrQ"
            + "PonOADpTov1AZ0m0AXS2RE8CerJE5wCdK9FTgM6T6KlA50v0NKCnS/QMoAskuhDomRJdBPQsiZ4N9ByJLgZ6rkTPA3q+RC8AeqFEHwH0IokuAbpUosuAXizR"
            + "5UBXSPQSoJdK9DKgl0v0CqBXSvQqoI+U6NVAr5HotUCvk+j1QFdK9FFAHy3RxwB9rERvADog0VVAV0t0DdBBia4FeqNE1wEdkuh6oDdJdAPQjRLdBHSzRLcA"
            + "fZxEh4GOSHQr0G0S3Q50h0TDfKtK8YMK87MqxQ8qzO+qFD+oML+rUvygngC0FC+o24HuctHdzvhAjTnjA3WHMz5QdzrjA1WKF1SkT3TO7+puZzyi9rra63O1"
            + "1++MN9S4q/0Bqb2TgE444xFVii9UbH/YRe9x4dnrwrPPgadQleIP9WSgpfhDhfgDz2HEuA/PTcSreqqr/i+66j/NJe/pLnnPcMl7pkveL7nkPcsl39mu/jnH"
            + "GZ+pUnyjQnyjSvGNCvGNer4zflMvkNK/CvSFEn0R0F+T6IuBvsRFX+rSx2UufVzu0scVLn1c6dLH1136uMqlj6td+rjGpY9vuPRxrUsf17n0cb1LHze49HGj"
            + "Sx83ueibXfLf4pL/Vpf8t7nkP+Aqf7ur/Ddd5e9wlb/Tpb+DLv3d5aLvdrUnxXMqxHOqFM+pEM+pUjynQjynSvGcCvGcKsVzKsRzqhTPqRDPqVI8p0I8p0rx"
            + "nArxnCrFcyrEc6oUz6kQz6lSPKdCPKdK8ZwK8ZwqxXMqxHOqFM+pEM+pUjynQjynSvEchfhFfUGiIf5QpfhOhfhOleI7FeI7VYrvVIjvVCm+UyG+U6X4ToX4"
            + "TpXiOxXiO1WK7+i/gH5FSn8V6NekdIifVCneoxA/qb+R6H8ALcV/KsR/qhT/qRD/qVL8p0L8p0rxH4X4S/2DRH8ItBQPqhAPqlI8qEI8qErxoArxoCrFgyrq"
            + "U4oHVYgHVSkeVFEeKR5UUR4pHlQRjxQPqtgfUjyoQjyoSvGgCvGgKsWDGvhnTYoHNYgHNcVZv6Y669c0Jx5Nl8r7gM6Q6Eyg/RKdBbQh0dlAT5LoyUDnSHQu"
            + "0FMkOg/oqRKdD/Q0iZ4O9Aypf3YALcWLGsSL2kzn+lsrktJnAT1boucAXSzRc4GeJ9HzgV4g0QuBPkKiFwFdItGlQJdJ9GKgyyW6AuglEr0U6GUSvRzoFRK9"
            + "EuhVEn0k0Ksleg3QayV6HdDrJboS6KMk+migj0nSDPtHih8ZxPPaBil/AOgqia4Gukaig0DXSvRGoOskOgR0vURvArpBohuBbpLoZqBbJPo4oMMSHQG6VaLb"
            + "gJb2n7QOoDdL9Bagt0r0NqCPl+jPAf15p//XpHhSg3hSk+JJDeJHrcuVX4ontRjQUvyo7QR6lyt/j5R+ItBSvKjBfK71ufJL8aEWB1qKBzWYv7SE099og05/"
            + "ow05/E0m/toxHnSIP/bLr18m5/LrpeQyfr2e3MCv3yR38OsIOcSvj5LH+PVp8n1+/Ql5iV9fIa/y6x/IH/n1b+RDfv2U/JsfqKhTH79Opjn8OoMW8OtcOo9f"
            + "y+hifl1JV+Hxefi75fxayq9+/MEMvv94Hr2KUG2Yy72H+PEHynn65Xxf0o+/RYRX/IFvO99eqHcz378s1PZp+207/xDokyX6I5KJv2WA8Sz+1jo/T/JYXu4S"
            + "aPkdqJVqp+Bxf3jQAh4GWThGtIbFoySrY/G9vCXU5SSCh5/MJRrU4CfztVOBmmbmZ+VcGo34tC+yB/A0Azxw2KPW3DS1LoKyJVBradpaLySU+vHnpPHwNvwB"
            + "Zn49X1yvFNdRcX1OXH8jrn8xr/gLVXjF3x7Ac0Hxd+0FyvV4HAK2WvYgmfYwKdryIJn9mI3Sj4jYfqKykzlCw8yrnaadjr2rnaGdKeop4ilQ9wiZg1JSXl5H"
            + "Lrsc+uFkdorIeT5+7I0tFx4i82ceIotGSNnTRL0PLqNkCfztGCGrzo6OkPljZI3MPBqZi4B5H556qn0J/xI6mTdUiN9ys6sA6NUkg11DJrNvkAXsWrKOXSep"
            + "dj3+nBGHuZ7yBQUY0n56sgB2Kqicn7g5QjbMHiM1o6T+cdLSWP40mf0wCW95mLSCctqndIySLbNHyedGyQkPku7Hyu+10WQQ+gmp8JEWDmkKyEnYjWQSu4lM"
            + "YTeTCnarBGWpdha3zElkrna2dg4e9aB9WTsX9I5Q9kFZFU8yGiE7mwCAnx4iOw+Q3NnlY2T3GImPkUFgVCSbziTsEzLZR4aypbbvAHXcCao4SGaxu3nbxVzA"
            + "OaJtvPuKdh7+pAmZqZ0PHkTRLgDuTDAJ7avw/wsHtYsA0dfAGAV5McC8RLs0FeZ+C+b+JMxTx8jpwPCAeYYMcwRgHgKY9wPM0bQwL7NhfmFimGBx2hXalakw"
            + "z7Zgnp2E+ZUxcgEwPGB+VYb5LYD5MMB8BGA+mhbm122Y504M8yqAebV2TSrMiy2YFydhXj5Gvg4MD5hXyTC/AzC/CzCfBJhPpYX5DRvmZRPDvBZgXqddL2Ce"
            + "hGfMwHXGCLnWgnntAeIHlDfCnQe+m3QJ37OA7znA9wMyjb0g4Zth45sB+G7g+KYCvhsmxncj4LuJ3CPw7RR+F/AdaCgz8R0AfGVj5E64W5yK76CM78eA7yeA"
            + "7yXA9zNpwCKqm7nzRFR3QMsXmAe3OaDcAlBu1W5L7dF7LVXdm+zR+8fIg8Dw0NiY3KMvA6JfAaJXoEdfS9ujB+wePTSxxm4HmN8kT6Zq7BFLY4+YGnsc7jw0"
            + "9oSssdcB3xuA703Q2Fsujd1ha+yxNBq7E6Ac1O5K1dj3LI19L6mxZ8bID4DhobHnZY39CRD9GX9IADT2dlqN3W1r7PsTa+wegHkv+WWqxn5kaexHpsZ+Cnce"
            + "GvuZrLH3Ad8HgO9voLG/uzR2n62xl9JobASgHNLuT9XYy5bGXk5q7LUx8ltgeGjsdVljHwGijwHRJ6Cxf6fV2AO2xl6dWGOjAPNBbSwV5lsWzLeSMP80Rv4K"
            + "DA+Yb0swFWheUchkRSWzFD0tzIdsmH+cGOa3AObD2iOpzu19C+b7pnP7O9x54PuH1LGKH/BlAT6DTFMmpXVu37ad24cT43sU8D2mPZ6K72ML38cmvs/gLhUf"
            + "JTI++KvkAb6pgG9aWnxP2Pj+PTG+7wC+72pPpuCjqsBHVY6PZsCdB75MGV8h4JsJ+IoA36y0+L5n4YM1yIT4ngJ8T2vfT8WXbeHLNvHlwp0HvikyvrmAbx7g"
            + "mw/4FqbF94yNL2difM8Cvue0H6Tim2bhm2biK4Q7D3wzZXylgK8M8C0GfBVp8T1v4yuYGN8LgO+H2oup+OZY+OaY+ObDnQe+BTK+5YBvBeBbCfiOTIvvRza+"
            + "eRPj+zHg+4n2Uiq+EgtfiYmvHO488FXI+NYBvvWArxLwHZ0W309tfIsnxvczwPdz7RcpbpAut/Att90gPXKMrgWGB8x1shvcAjC3Asxt4AY/l9YN/tJyg7AE"
            + "nxDmywDzV+xEsRSK43F4cF0wQo++mswao4HzDtHaEVp/7Owx2nIV8cGYVpVD9GhIP2hjzSLKJ6RI3UA/EsvHabCiIsp2oivdJFuJkVxlBylWdtlLyVwyWTNf"
            + "QMrUXrWXpA2i8SnQQLsyRrd0jNBObHj7ffbC1Ky5F0D0kUwlDrUPQO0n2TVnE82u+TV76XkklsfFabIivkJVBqVJmGq/FotEUIy5SNR+Q293r3Pz6I6DznWu"
            + "sgdy/lZ7XeSsEivr7ELIeoNQWVJZGmFZG+TS8go7W3uDY8jU3iQrhemsEWrJHKE7URsn3uVcpiunQf2n25Wolga03/GukTo+U3tL+72oFcJ/ko8/2TBC+68i"
            + "2eXqGB2swD/l0MTe++kpq6duGdXfyJ86qr/VUWHyzpJ5jsV4TnIxrpwF3X42yVLOITnKuWSB8hWyVjlPMtZ1trGuo6dqf+CyraPnwJ1prGVKWmP9o5vM1P6k"
            + "/VlIFEAgeFwtSPHlTWVj9Kuj9GsdZa6tF+UioilfA7u52NaYRvzaX7S/Co1NdrbxNsnEXxMWfVvI0UKF/rtcNnAFYHnH1u65QrsVKdpVHHpVDkujV4NGrwGN"
            + "fgM0ej2ZrdxAypUbJY1WaO8KjVZo7wmNVmjve2r0gwk1+jftQyHFy0KKbVyKgqQUBSr/q00kUYE6QuY7GBDELkorcxGXuQzMmii3gMy3gsy3gcwHwCvfToqU"
            + "O0GSg2BNd5GQcjfZqtzDdbAKXQLZZutgm62DbaYO+O9OoKWp/A4tTeN6qdMOXy8Tae3vtA8EQa0tEeNVHaGX3uXyN4eksa6a48A9TD/g0AzqHLj/0P5pz3Qq"
            + "TA6E+GYX9xyil97ncgZjoLeHeCt1BHdqffZo83ENMX6HT7FUfvc+3Gn87G7cItP5HW6RmWXxaVUGBzTXJwOS1Jb8l6l9pH0sUDYThSt+puk5rigao1eDS7/6"
            + "IXq9Qh6n14zSWw+6dPOI5Itn2qhnap8ABsYxTHJMY9jgp+w00eBmonCJpkkNzuINflMhHW4tPU7ylSekETTNbm+a2R6/w1nAHEE5irNlbPvf2mfCK9wsxskG"
            + "Pk6mlOGwWMzHBv4p02WDX+yTqfL0DiCfw51HMoB6Ejr1ezAYngIgTwP0Z8l85TmyXPkBOVZ5XhJjg07AuAm/o3BH+R3jwyFTV0hX6mR2p2vTVnkRcqq6JtRa"
            + "zn8XxXxZ0tldL0mmzHSd+88LzEPgKQ4OqManZ4gxcQjgZWB1Y/SuxvJReugY6KbRUbJkjToNfcm31uvls0bptwv0e9f7zDvfYwgaPOA8roYF/GpqJ5fQz4BU"
            + "VB+9G1rz6WaMNBuNVoGARnkVYojXSKHyGzIHrvPgWqr8luMtAyOZQnL0TOhuNJdy4TZUUqL79Sze5Hzd4B2fOhvok1MG5wcwP4zRh4S6loj9AfB70sRjKuwt"
            + "x9h/1x77uq2vHD03pR76hLueP3nU44FKn6LnidqOxnOjMfSAUPgJ91D4K0QPb0tDT7MdqaZPpU+KoTedyZVbt3o+tDONxkQ7tSLI9Y/Q7wVmF4/R79/nmnzf"
            + "g8behw76QGrQL8aeCitWDFyYlwPUp9uthEQr2SP0B2YrY/rb7ob+Dg39Axr6p9RQtt1Q5jgNzaCPi1FSL3zYpMIx/b0R+sMNhXPG6I+dLanKxyQDot9s5VO7"
            + "JZX49QIexmUIkSCU1At1K85dyOEDHgyvR2jtzqn0p/F7nMNQVaBIkT5LFFknnp1kQZEjLophUO7qR9VH/GqGZBpZ+mz+CC1Tn2OHL0UifIER93NXAKNmQc7i"
            + "1JxT6cvunDlg8n+C6c7MuUJAy5g9Ql8Zo792TXlqvoQpQ59rxub6PPo7Uf54GLU42fgLZ5bPnaaN0dfdNRRI07zffpLjF09y8O5t+o4wJbRd/jMJvCUNWpqv"
            + "LxAtdUI7GTwwn5lH35qmTtM9GpslNZZtN5ZtN5YNjTULc3pXOJFs3pgOQN60xGIDkJINeougSH9Y43uYtGx5kP55fWZBJsRED9BT8LroAXpWQWb5A/QvBZkw"
            + "Z+XRd+1pEmctZL6XR/8GjDz6oZv9d6+8//Bi/jOFCU39Cxiqm/mRg6mv9uurs/Lox2P00/VGHqMFxihTIJ71rfbnQMoBsjCPZQpmJjAPkKl5LEtiKKuzzJoZ"
            + "GPvV+X5Xe2wScrNs7gjZMM03RmoKMkfZ5I5pvkMsZ4TlXU0y52ZedhXJgDQ2bZrvLnsimENgDn4N9E59bK2qwhhowVvok5ycj0m9yvv1ZJgYiDoPFmILSI5a"
            + "QmaopaRYLSNHqItJpbqEhNWlZKe6jHxBXU4uVleS29RV5GF1LXlUXUeeUCvJM+pR5CX1aPJz9Rjymnos+aO6gbyjBshHahX5VK2GFVeQTlNrablaR9erIXqs"
            + "uoluUptoi3oct6UdGM/SiGVLcCdsCe6E4dKIZbg0IgzXRxv0hXDnIzm0mj8pxGf2l+v4voqfPEEu0fFN5yzyDDlPL9H2wwDL1EvBR5qm/hhYH/8JN4yGOqzn"
            + "mqyg4wCZ1zjCisQzzTxWnHyoOcrmlY+yRZB5lJVdTXyZB8h0IExa7R5lFY+T4ChbPsZW3ceHQg7JI2HRE36iRnwkPNuIqMm4Rd1MNHULKP1zJE/dSqaq28gS"
            + "9XiyQv08aVc7bX85lczWy/TFfFB1CB+dqZfrFUIYawGcbYZ2f3mIraHENSuq2x2raKuSJfbmwlwR8mjqIZZ3n+3RTN+5g6jqTsi+FBZwZvYzREi3vPwpMt1z"
            + "SQz88ZfF4BZnTE9OSeqJYHm7yTK1VwrYlttx53J7LbzcXAsDnGX6cjHnRbicqRsYalxyV9SujOorRBBLdXxhnf/Mp75KBP1Ux5fWzSXQVM0d2PLgVl8NgbWp"
            + "iQ4+o0HQhcIWPcDW43XWA+xo15SrJkiGOgidPSTJl2tDymVHCUi57FgOKRN/jF408hUxpx9rNkJPMRuhZ8m2ukHYah6rgruiUVaTxzbC3Sy4M+03CYnvl6l7"
            + "AdI+gHQyOUY9RYJ1rA3rWD4sGb87R8CaYQ+jPSICWIFwGss9/Ga5h9uczUeIY8dK/SIY3mn8dekK9QyyXD1TiktWCDQaLNrFKwH6GhtDv8CwxIVB8cCgeCx4"
            + "TE2cDe2fw1+frlDPldpeYrddare91g61FqfZN1PPl0abMDvPYGqdvQX3RdHDxYUA9QaSO1O1tsZwPI6wUKBwZvEYa0iOH50oORuoktwHUS+EEheBG/kayVcv"
            + "4e9mz1Evk7q12N7BKxY7eAWwhuWhHmBZr1cKsdJtB6pXpNsOxNdxrCUoSYa/R/Fqj56o2ivTVQveP121x+jH2gEWEwEWehvW7FqIqVfLAZbt+TboAVE+AKX5"
            + "j+GKAdzsjhyvg1FyvQQx0x4dmXoV3cCVV83ukaRUvKS8xSGl7Yro26C81EVEcg9Br8H69aC9jSfBxdDIDfc2gHvAG645n07UVq2+cSJZvukti16nh0T96bfU"
            + "9XpoY5M98XxXGH50hLZfTQz1EG1X+d42C19F9AOkFiak8AhrG2Ob+VS77SHWScmmsodYjJKrSCHc7KYEpt6GxTD5jrD4GEsc+OyNETYMJWDwhA8QhTaN6a8n"
            + "R84kok3O+ITk+9iOOR8RI7mrph4kunoXLBTuJpPVQzAf3U+K1AfIPHWUNKkPklZ1jGxWH7LtKQBh9EK9gS9fGm1fje9x4XRTWDRrjO2bDdEsvp3FvnCA+Ner"
            + "BeooO9MMDyaTKex0gchHWNjHTtelwfxt8EiPAownyBT1MRjU+HL8d+w1+lRYfZbpTVxzhXYHFOrNwlsX6i2mt9aPszVdIGYvep9rwfIkZAvba6m0Tu1HslPT"
            + "I2L11Dpx/T+BbG321tcmYcDTzejgPdyJeoh9iXlve6nyuybTbUmn6+3pt730Dnvb6zqx7XV0EawTbiDzZ91pYpzJf+hb2YBApqpj7KwDxKfARcLk3B2zHk4o"
            + "RbqS3E1RXwaX+yswklchZH6NzFd/TRapb5Cj1Dclt3u0jfpoEzXnjbt5pm9W1gmt1gj3BpH+uYW2qvBjDaeefi/pKUffIpxojr7VfFSjb9OPFzWm3bNS/+zY"
            + "szLryNQ/Z3fduSLWKSliNxBNOViUM6f7mFnXkOxZI+z8UXbRjciEW4g4Lskln33xoPQKoOL7hEyhVLLwd8DC3wX9fQCj/m+kUP0Q9PhPSW8ltt5KOBYGyIr0"
            + "z9t6S+33ThvpoDCyBUXXkMyiHN8tHO4IuzyJ8vKiFJTQvX6aJYUln4Av+BQ8BO52UjJDUyUlL7DhLdBPGMcYo8x6ka1fuLrp7qces8srxtjXPZ5vcBSaj+ha"
            + "BsnSMkmOlgVIDElJ0pDQ3tO3i2HyPtxN9LRI73KRNJNdDVjpf1HuGfbsf1NO79Zj1ss8HoNgh75TaK5KdKc6lX3DtSOhFUraUPVdeo+5MaOfKHYkVH233TtS"
            + "K3qvaKWP/VIMjCXiyaRXK7PkXUW9XwyN+IShoDbH4TUHvEJB/SSoKkGfk2Zd1auq4jSz7oC9WZdmVsf6B+2H2IViqWeOf8lRawsgur/bjmROEKN9Gix7r3sk"
            + "o6m8YpTd2JHrexGM9RZ3BK2VQChZCkvLMpKvLfZ+dKAPYagDfnOyPqzvEQaaPhBhd0tBjyKCnvIxdvsYO+gKerQlENotTROjDR1O0LMXltPMGU/m+n5EQVKX"
            + "o9VWpMaTnrH9voltY/VhLhP22w/Pc4Xv1grH9O8dFAKewl7wSP7+vSL5C/YL/XLyc1byqfab+XLyC1byFz2Tf2Qln+YJ7SUL2ume0H5ulT7DHnvlYuyxTPfI"
            + "2+yYlE4WI+9M+pL08oJXkKodLw8XURDvviR2nM/Sz3bPiKprRtQ+72h8iH8EnKmf4ynVy5ZUX9bPFclThC3phYvH9FcPWk/G9POFZZwt7Hqe/WSMPbwY/5Tj"
            + "nzJFb16s+JrLFaU53bMvrQcmhxNhctgNk0MvjL1+UqDFySxtgMzVTpLG4Tyd6BdwAPN0CneU3zG4M8fGEuU/faT7jv5VIcaQiLIK7ImNPVzBxQDkFW700qN7"
            + "bRjQ7wX0+wD9yWQq1DZD+4KEusB+2lIA09sFXJkFML1d8N89utcvFIhHCP+VO7LSfnQvEOOje/iryegLVAWi+OYCDS6LmtM9ktdOB1nOBFm+BLKcBX7vbFKk"
            + "nQtL7a9AqHAeWaydT1ZoF0j7UStt2Vbasq00ZcM7eircqfzuHLj7P38kr1+kfy19/z2K2ni0XDGOq4D/p+2/60DmG0DmG0Hmm6H/boH+u9W7//SL9UvM/tMv"
            + "hbuJ+k+/zE0i4ssF4pMF4jmpL5AYo+yJjgrzkhb37YD7DsB9J+C+CwKmu8ls7R7HG2MW7jn6xewBjnuOfinceeO2QPInlS4yU7/i/2CkHALEDwDiUUA8Bpp+"
            + "CDT9LYemzzrskTLha0P6Uhvx6SJoXZiKWGz7Am6x0Zse/aOA/nFA/wSg/y6sWJ+EMfE9Cf1CG/1CbveM353z36HX3vo/Rv8MoH8O0P8A0L8A6H8I6F/8/4Ue"
            + "zz4T6EehdfRSleN7KbCbbc3opSyRwFN5ymR6qyPw/RjtJZDpZyDTz0GmX4C3+iV4q1+BXK+QpdqrZL32muSpKm35KvWvC/kq9auEp6rUrxaeqlK/xtNTXfQ/"
            + "eqpv2J6qW4yf3PKnyOSKp0jWOB7KjEtfhyDtDVjCvQlyvuW99W57ptw0numiCT1TFBZA6RZ57Enssyf5Iu/ptIu8PxN8RyRLextwvgv98V7aRd619iLv2v98"
            + "kQdYr2M/Fq+dREV4UpA7RxljzzUsBqUuLhtlP7wq5bVMP75vO93H1A3SgyHtHxD0y68QFOjXQ7CN+1lT9CH84tT19pZ+Q8raB4PvG+0XA78gniuVQv8ugv6d"
            + "X+56Q65ivBfkSpL9rk8hGXoemaFPJSW6/Ep/qe3ZS/WbxCOlUv3m/+5lQP1G29PsEpaZD8jzAPnkch40VKTGDFMllDMBZRGZpM8iU/U5Esp8G2W+fpMYcfn6"
            + "zf9d3KPfMuFSRJ93mEuRWyeuauFhVnWbfkBUFRbB//Syp0g+2N9PriOTyuD60ij7+SH2skN705KP1PQykqUvhnVlhdTidP12r3etvmm/B2u9w71ojN61qUwb"
            + "Za82LM5jvxljr4+y33XA7R+s21H2J/nTZD4X6Ctg+b+S+PVV0B9HkmJ9NVmor5HebZnP35PCATJLvwOQUK/3pO4ERAdTF+Sqa0GuV4J53GVtsLIPgeuDDLfj"
            + "M9qcjDH210NsBK/vHmKjufSYMfbBIXY+MuB6eeYhlpPHPhxj/9iUxz6CSwNo17f4aTJzhH2Sxz4bZfNyNXKIzM9lLx4iiw6Q3LIxhQK9mF8W5SnamOI7QDIa"
            + "y0cVY0zJPkQOPEw2bXlQyT3EzrXuyIaHydItI8oUaPRBJf8QKTv875UlpjLj7CgAd/JmevBme/DmAu8o4E2ho8qCxvKmikdXq8pqLV/LV28iJ1bkayvW6wX6"
            + "qFKWpywdVZZfRVpG2LkFOuQ+0txzHqNXi6eoPFtOZo5/VFk9qqwfJVtMDtBH5ynH5imBUaV6VMHHqqPkBHN7+CyNHvjscnCXTVNontKQpzSpY0rLITLnPjCI"
            + "GNlN+hU/nUUvo1cq9cKOQ8T/KTkWvCn8Nz27zqf41c9gTaKbHJ/+APxHwQA+ITt9yuKP8DUORhQ7lVCfUv8JulnzAfpNpIS/k6TCStYgFWQq+ZjMgsIL2SxS"
            + "yorJMjaXrGHzyLFsPqljC0kTO4J0sBLyeVZKYmw52c1WkX62gsTZSjLIjiT72WryJbaWfJWtJ9eySjLCjiJPs6PJr9gx5B12LFXYBjqdVdNZLEhXsloaYBvp"
            + "RlZHj2ch2s/q6blAXwhpl7EWeiVrol9np9rRRBzGT5l+N2F0Jdmm36PfC0a9jO7S74PpgpA1NKqP4MRBZ5FT9EP6/fzp2hX2ewc1wsumvjj+FFDlMC0o4u2D"
            + "7IoKizKfR4gN3pzsbIyxtA9FjTfhi0lw3a0+4l+v4x8f/snAP5mzC/QCX0FGQeaY/gdoIYe3UKArS0QjObyRAp/JKFCfIlMK8BkP8DIET0OeVqAhL9PkJfEs"
            + "JNDvy0mm6qOnJf/T/2j/R0hOXh6PgC4XeFcKDcyyJTYXPJbE9rqHWm9ZTMomTB/jfkahmwjVH4J5+ltkqf4IVPxt/TF+HeUbHA/+P3JQHDbmwAAA";

    private static final String VSYNC_HELPER_GZIP_BASE64 =
            "H4sIAAAAAAAC/41VS08bVxT+Lh4zHmdCsAOG2GkwSUPsPHDzJMSQNAZMTHkVUxqoUnqxL87AeMYdj3l010WkrrqosglSI1WqlHVIRdxWqtpNF/lNUei5Awml"
            + "hapezH2cc8/9vu+ce/zqzS+/AbiGWhAN8KlQdPjRyNC8xFd4yuRWKTWxsCQKLkNjn2EZ7m0GXyI5E0CAIVp0bKO44BjFkuhecHn3SnXdKkzygnCCCOKICl3H"
            + "UTQxhPbCZWzbFNxiCJaE+27Rmhjdc8m7jmGV0sm5IJoRUhHWcRwtDE17LmPcfUhIynyNwZ/I5ZI5DRG0qWjXcQJRhtO2U0qZq0slM1UyF1dTgxJqxoOacfmM"
            + "BMqgV1d5JWe5wlnhJgPzgpzU8R5O0W2WWHOzDi+LcW7ZVTKPSHNcRydOk3mRm+YCLyyP2qWSKJJ5TsP7OKuiS8c5JPZpmF+vuqJMgO0aKdm6w9WwU5NE1CW6"
            + "gpfTKs4ztP1ThUzNMItSzyR8AVxiuHAAk1tx01gRcUk0/zdG/fJUSscHuEzp45WKsAjnpYOkHj3k3rQMcVWGuMZwMpE73DGAGwzhOC/yiktgsrvqeBhuygC9"
            + "MsDcf9+U1tGHfoaAa+8YGVoS/z5CvnfwoYq7OjIYYDh+gJ4MakWuzMOqi2p4iCANTk3kBjNTucHhofmZ/Oz4wPz03anhoen57GQ+SAkd1nEPOdKPylVYKwxd"
            + "/0s/gvgRRlWM6RjHxP5i2GWmGtWhcsVd917UnPT/WMeUpK+QRzmIaXyiYkbHp7i/7wnJ/JaEQzJVuFMVtDyEYk7FHEPHnmG8Vl4QTtZ2ytwdWisISpVt+akD"
            + "IL3xCpLu5zrm8QWFtqjop42y8NCNeD70awiASqj3wBp8m/r425cRF1aJ09uIu9wh9bKVan8Ai/Ty48ZehT6EoWJJxzLoBXZ5UGuuYaYKtlWoOY6w3JRpF5ar"
            + "qVH65muViu0QXY2YL+++SyUxkpyhYcAuEtpjo4YldnhO8wWTdppsK7/vnStUx3TgaN4lkGO8suun8UXykK4EMW/XnILIGtLQfgDdbomUqjpCfRPwYUS2HZoF"
            + "ZfPwRuoSnqVBdlQwlGnVSSOj0X/+JdhzmjBYnrMMEoKCMAKwUdl136Dj0v1eWK1D24Dy3Bet49gLtGovEPO9QMcmzvyK5OxLXAhf3EL3Jlq3cCV8fWf6FKry"
            + "DIpvCz1buLWF2/LGBu/GKEECWiE7WghtNGsnAicwjBi+JEscPm0bV8EYGlTcUZHclmiZ/I+QKyoQOG9pMZdWR8j+xzucdHs6+j0CEufz5r6+8GAd2bGLf+IE"
            + "wRzZgEQ7WUe+7xnUcbLHmk/9gGAsxB7+iEZat+Cz2KNy9JGRqeNBj38TsR41omqPnyAU8UfUzhZwgz9+iiMRfyeXd0TUiF/0aBFNo91rm+jYQFLZL1CBVIlJ"
            + "eQRNojTZESWi1VGikF4YInQeV3Ads7tCWSQIKKMaSXKU8heirLbiDMnWRXKdQwcSdEKeuUSnLuAGLqIXKeRwGXnavU8iPqBdBz1Yx018hTS+pib3DfrxLW7j"
            + "O+pkP1EX+xmD+B1DnvhnEdReQ2fbdK+PMZU6A31mfXrDGyQURVFfo1dRCGrVy6f7FywEiErGBwAA";

    private BtaGlfwBufferCompat() {
    }

    public static void prepare(@NonNull File lwjglComponentDir) {
        if (!lwjglComponentDir.isDirectory()) {
            Logging.i(TAG, "BTA GLFW component directory is missing: " + lwjglComponentDir.getAbsolutePath());
            return;
        }

        File[] jars = lwjglComponentDir.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            Logging.i(TAG, "BTA GLFW component contains no jars: " + lwjglComponentDir.getAbsolutePath());
            return;
        }

        for (File jar : jars) {
            try {
                byte[] current = readZipEntry(jar, GLFW_ENTRY);
                if (current == null) continue;

                String currentSha = sha256(current);
                byte[] currentHelper = readZipEntry(jar, VSYNC_HELPER_ENTRY);
                boolean helperReady = currentHelper != null
                        && VSYNC_HELPER_SHA256.equals(sha256(currentHelper));
                if (PATCHED_SHA256.equals(currentSha) && helperReady) {
                    Logging.i(TAG, "BTA 8 GLFW compatibility/VSync patch already active: " + jar.getName());
                    return;
                }

                if (!ORIGINAL_SHA256.equals(currentSha)
                        && !BUFFER_PATCH_SHA256.equals(currentSha)
                        && !API_PATCH_SHA256.equals(currentSha)
                        && !PATCHED_SHA256.equals(currentSha)) {
                    Logging.i(TAG, "BTA GLFW class has an unknown build; not patching "
                            + jar.getName() + " sha256=" + currentSha);
                    continue;
                }

                byte[] replacement = inflate(PATCHED_CLASS_GZIP_BASE64, 50 * 1024);
                byte[] helper = inflate(VSYNC_HELPER_GZIP_BASE64, 4 * 1024);
                if (!PATCHED_SHA256.equals(sha256(replacement))) {
                    throw new IOException("Embedded BTA GLFW v3 class failed integrity check");
                }
                if (!VSYNC_HELPER_SHA256.equals(sha256(helper))) {
                    throw new IOException("Embedded BTA VSync helper failed integrity check");
                }

                rewriteJar(jar, replacement, helper);

                byte[] installed = readZipEntry(jar, GLFW_ENTRY);
                byte[] installedHelper = readZipEntry(jar, VSYNC_HELPER_ENTRY);
                if (installed == null || !PATCHED_SHA256.equals(sha256(installed))) {
                    throw new IOException("BTA GLFW jar verification failed after patch: " + jar.getAbsolutePath());
                }
                if (installedHelper == null || !VSYNC_HELPER_SHA256.equals(sha256(installedHelper))) {
                    throw new IOException("BTA VSync helper verification failed after patch: " + jar.getAbsolutePath());
                }

                Logging.i(TAG, "Patched BTA 8 GLFW buffer/API/VSync compatibility in " + jar.getAbsolutePath());
                return;
            } catch (Throwable throwable) {
                Logging.e(TAG, "Unable to patch BTA GLFW component jar " + jar.getAbsolutePath(), throwable);
            }
        }

        Logging.i(TAG, "No compatible BTA GLFW class was found to patch in "
                + lwjglComponentDir.getAbsolutePath());
    }

    private static void rewriteJar(
            @NonNull File jar,
            @NonNull byte[] replacement,
            @NonNull byte[] helper
    ) throws IOException {
        File temp = new File(jar.getParentFile(), jar.getName() + ".bta-glfw.tmp");
        //noinspection ResultOfMethodCallIgnored
        temp.delete();

        boolean replaced = false;
        boolean helperWritten = false;
        try (ZipFile input = new ZipFile(jar);
             ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp, false))) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            byte[] buffer = new byte[64 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (isJarSignatureEntry(name)) continue;
                if (name.startsWith("META-INF/droidbridge/bta_glfw_bta8_compat_v")) continue;

                ZipEntry outEntry = new ZipEntry(name);
                outEntry.setTime(entry.getTime());
                output.putNextEntry(outEntry);
                if (GLFW_ENTRY.equals(name)) {
                    output.write(replacement);
                    replaced = true;
                } else if (VSYNC_HELPER_ENTRY.equals(name)) {
                    output.write(helper);
                    helperWritten = true;
                } else if (!entry.isDirectory()) {
                    try (InputStream stream = input.getInputStream(entry)) {
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                output.closeEntry();
            }

            if (!replaced) {
                throw new IOException("Missing " + GLFW_ENTRY + " in " + jar.getAbsolutePath());
            }
            if (!helperWritten) {
                ZipEntry helperEntry = new ZipEntry(VSYNC_HELPER_ENTRY);
                output.putNextEntry(helperEntry);
                output.write(helper);
                output.closeEntry();
            }

            ZipEntry marker = new ZipEntry(MARKER_ENTRY);
            output.putNextEntry(marker);
            output.write("bta8-glfw-buffer-api-vsync-compat-v3\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        } catch (Throwable throwable) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            if (throwable instanceof IOException) throw (IOException) throwable;
            throw new IOException("Unable to rewrite BTA LWJGL jar", throwable);
        }

        File backup = new File(jar.getParentFile(), jar.getName() + ".bta-glfw.backup");
        if (!backup.isFile()) copyFile(jar, backup);

        if (jar.exists() && !jar.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("Unable to replace BTA LWJGL jar: " + jar.getAbsolutePath());
        }
        if (!temp.renameTo(jar)) {
            copyFile(temp, jar);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static boolean isJarSignatureEntry(@NonNull String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC"));
    }

    private static byte[] inflate(@NonNull String encoded, int expectedSize) throws IOException {
        byte[] compressed = Base64.decode(encoded, Base64.DEFAULT);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream(expectedSize)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static byte[] readZipEntry(@NonNull File jar, @NonNull String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream input = zip.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.max(0, entry.getSize()))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    @NonNull
    private static String sha256(@NonNull byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static void copyFile(@NonNull File source, @NonNull File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }
}
