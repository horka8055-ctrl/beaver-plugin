name: Build BeaverPlugin

# Собирается автоматически при каждой загрузке файлов в ветку main,
# а также вручную через кнопку "Run workflow" на вкладке Actions.
on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Скачать код репозитория
        uses: actions/checkout@v4

      - name: Установить Java 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Собрать плагин (mvn clean package)
        run: mvn -B clean package

      - name: Загрузить готовый jar как файл для скачивания
        uses: actions/upload-artifact@v4
        with:
          name: BeaverPlugin-jar
          path: target/BeaverPlugin.jar
