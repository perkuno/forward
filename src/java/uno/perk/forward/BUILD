import os

annotation_processor(
  name='forward',
  processors=['uno.perk.forward.apt.Processor'],
  sources=rglobs('*.java'),
  dependencies=[
    '3rdparty:javapoet',
  ],
  provides=artifact(
    org='uno.perk',
    name='forward',
    repo=repository(
      name='maven-central',
      url='https://oss.sonatype.org/#stagingRepositories',
      push_db_basedir=os.path.join(get_buildroot(), 'build-support', 'publish', 'pushdb')
    ),
    publication_metadata=ossrh(
      description='Forwarder code-generation for Java 1.6+.',
      url='https://github.com/perkuno/forward',
      licenses=[
        license(
          name='Apache License, Version 2.0',
          url='http://www.apache.org/licenses/LICENSE-2.0'
        ),
      ],
      developers=[
        developer(
          name='John Sirois',
          email='john.sirois@gmail.com'
        )
      ],
      scm=github(
        user='perkuno',
        repo='forward'
      )
    )
  )
)