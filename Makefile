.PHONY: all
all: target/git-chooser-build-branches.hpi

target/git-chooser-build-branches.hpi target/git-chooser-build-branches.jar: $(shell find src/main)
	docker run --rm -t -i -u $(shell id -u) -v $(shell pwd):/src jamesdbloom/docker-java7-maven sh -c 'cd /src && mvn package -Dmaven.repo.local=/src/target/.m2 -Dmaven.test.skip=true'
