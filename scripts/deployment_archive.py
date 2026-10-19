"""Create and verify an allowlisted Docker source archive without runtime state."""

from pathlib import Path, PurePosixPath
import re
import stat
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

from release_version import parse_version


ROOT_FILES = {
    "AGENTS.md", "README.md", "LICENSE", "gradlew", "gradlew.bat",
    "gradle.properties", "build.gradle", "settings.gradle", "Dockerfile",
    "docker-compose.yml", ".dockerignore", ".env.example",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "velocity-plugin/build.gradle", "docker/entrypoint.sh", "docker/runtime.py",
    "docker/test_runtime.py", "docker/compose.py", "docker/test_compose.py", "docker/test_capacity.py",
}
REQUIRED_FILES = {
    "README.md", "LICENSE", "gradlew", "gradle.properties", "build.gradle", "settings.gradle",
    "Dockerfile", "docker-compose.yml", ".dockerignore", ".env.example",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "velocity-plugin/build.gradle", "docker/entrypoint.sh", "docker/runtime.py",
    "docs/DOCKER.md",
    "src/main/resources/fabric.mod.json",
    "src/main/java/com/deathswap/DeathSwapMod.java",
    "velocity-plugin/src/main/java/com/deathswap/proxy/DeathSwapProxyPlugin.java",
    "docker/compose.py",
}
SOURCE_PREFIXES = (
    "src/main/java/", "src/gametest/java/", "src/test/java/",
    "velocity-plugin/src/main/java/", "velocity-plugin/src/test/java/",
)
RESOURCE_PREFIXES = ("src/main/resources/", "src/gametest/resources/", "velocity-plugin/src/main/resources/")


def allowed_source(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        return False
    if name in ROOT_FILES:
        return True
    if any(part.startswith(".") or part in {"build", "run", "__pycache__"} for part in path.parts):
        return False
    if name.startswith(SOURCE_PREFIXES) and path.suffix == ".java":
        return True
    if name.startswith(RESOURCE_PREFIXES) and path.suffix in {".json", ".png", ".mcmeta", ".properties"}:
        return True
    return ((len(path.parts) == 2 and path.parts[0] == "scripts" and path.suffix == ".py")
            or (len(path.parts) == 2 and path.parts[0] == "docs" and path.suffix == ".md"))


def create_archive(root, destination, version):
    parse_version(version)
    root = Path(root).resolve()
    candidates = [root / name for name in ROOT_FILES]
    for directory in {*SOURCE_PREFIXES, *RESOURCE_PREFIXES, "scripts/", "docs/"}:
        candidates.extend((root / directory).rglob("*"))
    sources = {path.relative_to(root).as_posix(): path for path in candidates
               if path.is_file() and allowed_source(path.relative_to(root).as_posix())}
    if missing := REQUIRED_FILES - sources.keys():
        raise ValueError(f"Missing Docker deployment sources: {sorted(missing)}")
    with ZipFile(destination, "w", compression=ZIP_DEFLATED) as archive:
        for name, path in sorted(sources.items()):
            if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if parent.is_relative_to(root)):
                raise ValueError(f"Source symlink is not permitted in deployment archive: {name}")
            data = path.read_bytes()
            if name in REQUIRED_FILES and not data:
                raise ValueError(f"Empty Docker deployment source: {name}")
            if name == ".env.example":
                text = re.sub(r"(?m)^DEATHSWAP_VERSION=.*\n?", "", data.decode("utf-8"))
                data = (text.rstrip() + f"\n\n# Version selected for this release.\nDEATHSWAP_VERSION={version}\n").encode()
            if name == "gradle.properties":
                data = re.sub(rb"(?m)^mod_version=.*$", f"mod_version={version}".encode(), data)
            if name == "Dockerfile":
                data = re.sub(rb"(?m)^ARG DEATHSWAP_VERSION=.*$", f"ARG DEATHSWAP_VERSION={version}".encode(), data)
            if name == "docker-compose.yml":
                data = re.sub(rb"\$\{DEATHSWAP_VERSION:-[^}]+\}",
                              ("${DEATHSWAP_VERSION:-" + version + "}").encode(), data)
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.create_system = 3
            executable = name in {"gradlew", "docker/entrypoint.sh", "docker/compose.py"}
            info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
            archive.writestr(info, data)
        info = ZipInfo("RELEASE_VERSION", date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = (stat.S_IFREG | 0o644) << 16
        archive.writestr(info, version + "\n")
    verify_archive(destination, version)
    return Path(destination)


def verify_archive(path, version):
    parse_version(version)
    with ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("Docker deployment archive failed its ZIP integrity check")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate files in Docker deployment archive")
        if missing := (REQUIRED_FILES | {"RELEASE_VERSION"}) - set(names):
            raise ValueError(f"Missing Docker deployment files: {sorted(missing)}")
        for info in archive.infolist():
            if info.filename != "RELEASE_VERSION" and not allowed_source(info.filename):
                raise ValueError(f"Unexpected file in Docker deployment archive: {info.filename}")
            if info.filename in REQUIRED_FILES and info.file_size == 0:
                raise ValueError(f"Empty Docker deployment file: {info.filename}")
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Symlink in Docker deployment archive: {info.filename}")
        if archive.read("RELEASE_VERSION").decode().strip() != version:
            raise ValueError("Docker deployment version does not match this build")
        selections = re.findall(r"(?m)^DEATHSWAP_VERSION=(.*)$", archive.read(".env.example").decode())
        configured = re.findall(r"(?m)^mod_version=(.*)$", archive.read("gradle.properties").decode())
        if configured != [version]:
            raise ValueError("Docker Gradle version does not match this build")
        docker_versions = re.findall(r"(?m)^ARG DEATHSWAP_VERSION=(.*)$", archive.read("Dockerfile").decode())
        compose_versions = re.findall(r"\$\{DEATHSWAP_VERSION:-([^}]+)\}",
                                      archive.read("docker-compose.yml").decode())
        if docker_versions != [version] or not compose_versions or set(compose_versions) != {version}:
            raise ValueError("Docker image defaults do not match this release version")
        if selections != [version]:
            raise ValueError("Docker deployment build version does not match this build")
    return Path(path)
