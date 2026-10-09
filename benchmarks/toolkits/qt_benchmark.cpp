#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDataStream>
#include <QElapsedTimer>
#include <QFile>
#include <QImage>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPainter>
#include <QPainterPath>
#include <QTransform>
#include <cmath>
#include <iostream>
#include <vector>

static void message(const QJsonObject &value) {
    std::cout << QJsonDocument(value).toJson(QJsonDocument::Compact).constData() << std::endl;
}
int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    if (argc != 5) return 2;
    const QString output = argv[2];
    const int warmup = std::stoi(argv[3]), measured = std::stoi(argv[4]);
    QFile file(argv[1]); if (!file.open(QIODevice::ReadOnly)) return 3;
    const auto bytes = file.readAll();
    QDataStream data(bytes); data.setByteOrder(QDataStream::BigEndian); data.setFloatingPointPrecision(QDataStream::SinglePrecision);
    char magic[8]; data.readRawData(magic, 8); if (QByteArray(magic, 8) != "BYTCMP01") return 4;
    qint32 prototypeCount; data >> prototypeCount;
    std::vector<QPainterPath> paths;
    for (int p = 0; p < prototypeCount; p++) {
        qint32 inputs; data >> inputs;
        if (data.skipRawData(inputs * 20) != inputs * 20) return 5;
        qint32 outlines; data >> outlines;
        QPainterPath path; path.setFillRule(Qt::WindingFill);
        for (int o = 0; o < outlines; o++) {
            qint32 size; data >> size;
            for (int i = 0; i < size; i += 2) {
                float x, y; data >> x >> y;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.closeSubpath();
        }
        paths.push_back(path);
    }
    if (data.status() != QDataStream::Ok || !data.atEnd()) return 6;
    message({{"event", "ready"}, {"runtime", qVersion()}, {"scene_sha256", QString(QCryptographicHash::hash(bytes, QCryptographicHash::Sha256).toHex())}});
    std::string line;
    while (std::getline(std::cin, line) && line != "quit") {
        const auto c = QString::fromStdString(line).split(',');
        const QString name = c[0]; const int width = c[1].toInt(), height = c[2].toInt(), count = c[3].toInt();
        const bool alpha = c[4] == "true"; const float zoom = c[5].toFloat();
        QImage image(width, height, QImage::Format_ARGB32_Premultiplied);
        const int columns = std::max(1, int(std::ceil(std::sqrt(count * 1920.0 / 1080.0))));
        const int rows = std::max(1, (count + columns - 1) / columns);
        const float sx = width / 1920.f, sy = height / 1080.f;
        std::vector<QTransform> transforms;
        for (int i = 0; i < count; i++) {
            const float x = 6.f + (i % columns) * (1908.f / columns), y = 6.f + (i / columns) * (1068.f / rows);
            transforms.emplace_back(sx * zoom, 0, 0, sy * zoom, float(x * sx), float(y * sy));
        }
        QJsonArray times;
        for (int i = -warmup; i < measured; i++) {
            QElapsedTimer timer; timer.start();
            image.fill(0xffffffff);
            QPainter painter(&image); painter.setRenderHint(QPainter::Antialiasing, true);
            painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
            painter.setPen(Qt::NoPen); painter.setBrush(QColor(0x33, 0x41, 0x55, alpha ? 0x66 : 0xff));
            for (int j = 0; j < count; j++) {
                painter.setWorldTransform(transforms[j]);
                painter.drawPath(paths[j % prototypeCount]);
            }
            painter.end();
            const auto elapsed = timer.nsecsElapsed();
            if (i >= 0) times.append(double(elapsed));
        }
        if (!image.save(output + '/' + name + ".png")) return 7;
        message({{"event", "case"}, {"name", name}, {"wall_ns", times}});
        std::getline(std::cin, line);
    }
}
