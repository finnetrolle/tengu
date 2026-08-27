package ru.finnetrolle.tengu.server

/** Версии сервера. MANIFEST_VERSION бампируется при ЛЮБОМ изменении поверхности тулов. */
object ServerInfo {
    const val VERSION = "0.1.0"
    // v2: + jira (auth, issues); v3: + jira projects list;
    // v4: поля манифеста inDefault (key/title/state) попадают в провод
    const val MANIFEST_VERSION = 4
}
