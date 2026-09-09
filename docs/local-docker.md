# Локальный Tengu в одном контейнере

Этот профиль запускает только Tengu server. Jira PAT хранится в `tmpfs` внутри
контейнера и исчезает при его остановке. Docker volume и файл с PAT на хосте не
создаются.

## 1. Запустить сервер

Нужны Docker с Compose и Git. Получи исходники, укажи URL Jira и подними контейнер:

```sh
git clone https://github.com/finnetrolle/tengu.git
cd tengu
export TENGU_JIRA_BASE_URL="https://jira.corp"
docker compose -f compose.local.yml up --build -d
```

Сервер доступен только локально на `http://127.0.0.1:8080`. Проверка:

```sh
curl -s http://127.0.0.1:8080/v1/health
```

По умолчанию локальный hub token равен `tengu-local`. Если на машине есть другие
пользователи, задай свой токен перед запуском контейнера:

```sh
export TENGU_HUB_TOKEN="$(openssl rand -hex 32)"
docker compose -f compose.local.yml up --build -d
```

Hub token не является Jira PAT: он только разрешает CLI обращаться к локальному
Tengu server.

## 2. Настроить CLI

Установи CLI для своей ОС по [инструкции установки](installation.md). При сборке
macOS CLI из исходников сначала выполни `./gradlew :cli:linkReleaseExecutableMacosArm64`
на Mac с полным Xcode.

Свяжи CLI с контейнером. Подставь значение `TENGU_HUB_TOKEN`, если переопределял
его на предыдущем шаге:

```sh
tengu setup --url http://127.0.0.1:8080 --token tengu-local
```

## 3. Ввести Jira PAT без истории shell

Выполни этот шаг самостоятельно в обычном Terminal.app или iTerm, не в чате и
не в интегрированном терминале Codex. Блок рассчитан на Bash; из zsh сначала
запусти `bash`. На Windows используй [ввод PAT через PowerShell](windows.md#4-подключить-jira):

```sh
printf 'Jira PAT: '
IFS= read -r -s TENGU_JIRA_PAT
printf '\n'
printf '%s' "$TENGU_JIRA_PAT" | tengu jira auth login --token -
unset TENGU_JIRA_PAT
```

PAT не отображается, не попадает в аргументы процесса и историю shell. Tengu
проверяет его через Jira `/rest/api/2/myself`, после чего записывает в
`/run/tengu-secrets` внутри контейнера.

Проверить доступ можно без вывода метаданных токена:

```sh
tengu jira projects list
```

После этого Codex может пользоваться Jira только через команды `tengu jira ...`.
Полное значение PAT сервер наружу не возвращает.

## 4. Остановить

```sh
docker compose -f compose.local.yml stop
```

При остановке `tmpfs` уничтожается. После следующего `start` Jira PAT потребуется
ввести заново. Чтобы удалить и сам контейнер:

```sh
docker compose -f compose.local.yml down
```

## Ограничение гарантии

`tmpfs` не создаёт постоянный файл или Docker volume и очищается при остановке
контейнера. Это не защита от администратора работающей машины, дампа памяти или
swap операционной системы. Для защиты от форензики диска включи FileVault и не
разрешай Docker использовать незашифрованный swap.
